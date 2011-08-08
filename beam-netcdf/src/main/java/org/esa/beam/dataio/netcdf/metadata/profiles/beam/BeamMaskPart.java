/*
 * Copyright (C) 2011 Brockmann Consult GmbH (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package org.esa.beam.dataio.netcdf.metadata.profiles.beam;

import com.bc.ceres.binding.PropertyContainer;
import org.esa.beam.dataio.netcdf.ProfileReadContext;
import org.esa.beam.dataio.netcdf.ProfileWriteContext;
import org.esa.beam.dataio.netcdf.metadata.ProfilePartIO;
import org.esa.beam.dataio.netcdf.util.Constants;
import org.esa.beam.dataio.netcdf.util.ReaderUtils;
import org.esa.beam.framework.dataio.ProductIOException;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Mask;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductNodeGroup;
import ucar.ma2.Array;
import ucar.ma2.DataType;
import ucar.nc2.Attribute;
import ucar.nc2.NetcdfFile;
import ucar.nc2.NetcdfFileWriteable;
import ucar.nc2.Variable;

import java.awt.Color;
import java.io.IOException;

public class BeamMaskPart extends ProfilePartIO {

    public static final String EXPRESSION = "expression";
    public static final String COLOR = "color";
    public static final String MASK_OVERLAYS = "mask_overlays";
    public static final String TRANSPARENCY = "transparency";

    public static final int INDEX_RED = 0;
    public static final int INDEX_GREEN = 1;
    public static final int INDEX_BLUE = 2;
    public static final int INDEX_ALPHA = 3;

    public static final String SUFFIX_MASK = "_mask";

    @Override
    public void decode(ProfileReadContext ctx, Product p) throws IOException {
        readMasks(ctx, p);
        readMaskOverlays(ctx, p);
    }

    @Override
    public void preEncode(ProfileWriteContext ctx, Product p) throws IOException {
        NetcdfFileWriteable ncFile = ctx.getNetcdfFileWriteable();
        writeMasks(p, ncFile);
        writeMaskOverlays(p, ncFile);
    }

    private static void readMasks(ProfileReadContext ctx, Product p) throws ProductIOException {
        for (Variable variable : ctx.getNetcdfFile().getVariables()) {
            if (variable.getRank() != 0 || !variable.getName().endsWith(SUFFIX_MASK)) {
                continue;
            }
            final Attribute expressionAttribute = variable.findAttribute(EXPRESSION);
            if (expressionAttribute == null || !expressionAttribute.isString()) {
                continue;
            }
            String maskName;
            final Attribute nameAttribute = variable.findAttribute(Constants.ORIG_NAME_ATT_NAME);
            if (nameAttribute != null) {
                maskName = nameAttribute.getStringValue();
            } else {
                String variableName = variable.getName();
                maskName = variableName.substring(0, variableName.lastIndexOf(SUFFIX_MASK));
            }
            final Mask mask = new Mask(maskName, p.getSceneRasterWidth(), p.getSceneRasterHeight(),
                                       Mask.BandMathsType.INSTANCE);
            mask.setDescription(variable.getDescription());

            final Attribute transparencyAttribute = variable.findAttribute(TRANSPARENCY);
            if (transparencyAttribute != null) {
                mask.setImageTransparency(transparencyAttribute.getNumericValue().doubleValue());
            }

            final Attribute colorAttribute = variable.findAttribute(COLOR);
            if (colorAttribute != null && colorAttribute.getLength() >= 3
                && colorAttribute.getLength() <= 4) {
                mask.setImageColor(createColor(colorAttribute));
            }

            final PropertyContainer imageConfig = mask.getImageConfig();
            final String expression = expressionAttribute.getStringValue();
            imageConfig.setValue(Mask.BandMathsType.PROPERTY_NAME_EXPRESSION, expression);

            p.getMaskGroup().add(mask);
        }
    }

    private static Color createColor(Attribute colorAttribute) {
        final int r = colorAttribute.getNumericValue(INDEX_RED).intValue();
        final int g = colorAttribute.getNumericValue(INDEX_GREEN).intValue();
        final int b = colorAttribute.getNumericValue(INDEX_BLUE).intValue();
        final Color color;
        if (colorAttribute.getLength() > 3) {
            final int a = colorAttribute.getNumericValue(INDEX_ALPHA).intValue();
            color = new Color(r, g, b, a);
        } else {
            color = new Color(r, g, b);
        }
        return color;
    }

    private static void readMaskOverlays(ProfileReadContext ctx, Product p) {
        NetcdfFile netcdfFile = ctx.getNetcdfFile();
        for (Band band : p.getBands()) {
            String variableName = ReaderUtils.getVariableName(band);
            final Variable variable = netcdfFile.getRootGroup().findVariable(variableName);
            final Attribute attribute = variable.findAttribute(MASK_OVERLAYS);
            if (attribute != null) {
                final ProductNodeGroup<Mask> maskGroup = p.getMaskGroup();
                final String[] maskNames = attribute.getStringValue().split(" ");
                for (String maskName : maskNames) {
                    final Mask mask = maskGroup.get(maskName);
                    if (mask != null) {
                        band.getOverlayMaskGroup().add(mask);
                    }
                }
            }
        }
    }

    private static void writeMasks(Product p, NetcdfFileWriteable ncFile) {
        final ProductNodeGroup<Mask> maskGroup = p.getMaskGroup();
        final String[] maskNames = maskGroup.getNodeNames();
        for (String maskName : maskNames) {
            final Mask mask = maskGroup.get(maskName);
            if (Mask.BandMathsType.INSTANCE == mask.getImageType()) {

                final String scalar = ""; // indicating a scalar variable (without dimension)
                String variableName = ReaderUtils.getVariableName(mask);
                final Variable variable = ncFile.addVariable(variableName + SUFFIX_MASK, DataType.BYTE, scalar);
                if (!variableName.equals(maskName)) {
                    variable.addAttribute(new Attribute(Constants.ORIG_NAME_ATT_NAME, maskName));
                }

                final String description = mask.getDescription();
                if (description != null && description.trim().length() > 0) {
                    variable.addAttribute(new Attribute("title", description));
                }

                final PropertyContainer imageConfig = mask.getImageConfig();
                final String expression = (String) imageConfig.getValue(Mask.BandMathsType.PROPERTY_NAME_EXPRESSION);
                variable.addAttribute(new Attribute(EXPRESSION, expression));

                final Color color = mask.getImageColor();
                final int[] colorValues = new int[4];
                colorValues[INDEX_RED] = color.getRed();
                colorValues[INDEX_GREEN] = color.getGreen();
                colorValues[INDEX_BLUE] = color.getBlue();
                colorValues[INDEX_ALPHA] = color.getAlpha();
                variable.addAttribute(new Attribute(COLOR, Array.factory(colorValues)));

                final double transparency = mask.getImageTransparency();
                variable.addAttribute(new Attribute(TRANSPARENCY, transparency));
            } else if (Mask.RangeType.INSTANCE == mask.getImageType()) {
                // todo se -- implement
            } else if (Mask.VectorDataType.INSTANCE == mask.getImageType()) {
                // todo se -- implement
            }
        }
    }

    private static void writeMaskOverlays(Product p, NetcdfFileWriteable ncFile) {
        for (Band band : p.getBands()) {
            final ProductNodeGroup<Mask> maskGroup = band.getOverlayMaskGroup();
            if (maskGroup.getNodeCount() >= 1) {
                final String[] maskNames = maskGroup.getNodeNames();
                final StringBuilder overlayNames = new StringBuilder();
                for (String maskName : maskNames) {
                    overlayNames.append(maskName).append(" ");
                }
                String variableName = ReaderUtils.getVariableName(band);
                final Variable variable = ncFile.getRootGroup().findVariable(variableName);
                variable.addAttribute(new Attribute(MASK_OVERLAYS, overlayNames.toString().trim()));
            }
        }
    }
}