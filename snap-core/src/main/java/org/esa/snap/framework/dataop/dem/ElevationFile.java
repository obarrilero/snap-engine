/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
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
package org.esa.snap.framework.dataop.dem;

import org.esa.snap.framework.dataio.ProductReader;
import org.esa.snap.framework.datamodel.Product;
import org.esa.snap.framework.dataop.downloadable.ftpUtils;
import org.esa.snap.util.SystemUtils;
import org.esa.snap.util.io.FileUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Holds information about a dem file.
 */
public abstract class ElevationFile {

    protected File localFile;
    private final File localZipFile;
    private final ProductReader productReader;
    protected boolean localFileExists = false;
    protected boolean remoteFileExists = true;
    private boolean errorInLocalFile = false;
    private ElevationTile tile = null;
    private ftpUtils ftp = null;
    private Map<String, Long> fileSizeMap = null;
    private boolean unrecoverableError = false;

    public ElevationFile(final File localFile, final ProductReader reader) {
        this.localFile = localFile;
        this.localZipFile = new File(localFile.getParentFile(),
                FileUtils.getFilenameWithoutExtension(localFile) + ".zip");
        this.productReader = reader;
    }

    public void dispose() {
        try {
            if (ftp != null)
                ftp.disconnect();
            ftp = null;
            tile.dispose();
            tile = null;
        } catch (Exception e) {
            //
        }
    }

    public String getFileName() {
        return localFile.getName();
    }

    public final ElevationTile getTile() throws IOException {
        if (tile == null) {
            if (!remoteFileExists && !localFileExists)
                return null;
            getFile();
        }
        return tile;
    }

    protected ElevationTile createTile(final Product product) throws IOException {
        return null;
    }

    protected boolean findLocalFile() {
        return (localFile.exists() && localFile.isFile()) || (localZipFile.exists() && localZipFile.isFile());
    }

    private synchronized void getFile() throws IOException {
        try {
            if (tile != null) return;
            if (!localFileExists && !errorInLocalFile) {
                localFileExists = findLocalFile();
            }
            if (localFileExists) {
                getLocalFile();
            } else if (remoteFileExists) {
                if (getRemoteFile()) {
                    getLocalFile();
                }
            }
            if (tile != null) {
                errorInLocalFile = false;
            } else {
                if (!remoteFileExists && localFileExists) {
                    System.out.println("Unable to reader product " + localFile.getAbsolutePath());
                }
                localFileExists = false;
                errorInLocalFile = true;
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
            tile = null;
            localFileExists = false;
            errorInLocalFile = true;
            if (unrecoverableError) {
                throw new IOException(e);
            }
        }
    }

    private void getLocalFile() throws IOException {
        File dataFile = localFile;
        Product product = null;
        if (dataFile.exists()) {
            product = productReader.readProductNodes(dataFile, null);
        } else if(localZipFile.exists()) {
            product = productReader.readProductNodes(localZipFile, null);
        }

        if (product != null) {
            tile = createTile(product);
        }
    }

    protected abstract String getRemoteFTP();

    protected abstract String getRemotePath();

    protected abstract boolean getRemoteFile() throws IOException;

    protected boolean getRemoteHttpFile(final String baseUrl) throws IOException {
        final String remotePath = baseUrl + localZipFile.getName();
        SystemUtils.LOG.info("http retrieving " + remotePath);
        try {
            downloadFile(new URL(remotePath), localZipFile);

            return true;
        } catch (Exception e) {
            // no need to alarm the user. Tiles may not be found because they are in the ocean or outside valid areas
            SystemUtils.LOG.warning("http error:" + e.getMessage() + " on " + remotePath);
            remoteFileExists = false;
        }
        return false;
    }

    /**
     * Downloads a file from the specified URL to the specified local target directory.
     * The method uses a Swing progress monitor to visualize the download process.
     *
     * @param fileUrl      the URL of the file to be downloaded
     * @param localZipFile the target file
     * @return File the downloaded file
     * @throws IOException if an I/O error occurs
     */
    private static File downloadFile(final URL fileUrl, final File localZipFile) throws IOException {
        final File outputFile = new File(localZipFile.getParentFile(), new File(fileUrl.getFile()).getName());
        final URLConnection urlConnection = fileUrl.openConnection();
        final int contentLength = urlConnection.getContentLength();
        final InputStream is = new BufferedInputStream(urlConnection.getInputStream(), contentLength);
        final OutputStream os;
        try {
            os = new BufferedOutputStream(new FileOutputStream(outputFile));
        } catch (IOException e) {
            is.close();
            throw e;
        }

        try {
            //final StatusProgressMonitor status = new StatusProgressMonitor(contentLength,
            //        "Downloading " + localZipFile.getName() + "... ");
            //status.setAllowStdOut(false);

            final int size = 32768;
            final byte[] buf = new byte[size];
            int n;
            int total = 0;
            while ((n = is.read(buf, 0, size)) > -1) {
                os.write(buf, 0, n);
                total += n;
                //status.worked(total);
            }
            //status.done();

            while (true) {
                final int b = is.read();
                if (b == -1) {
                    break;
                }
                os.write(b);
            }
        } catch (IOException e) {
            outputFile.delete();
            throw e;
        } finally {
            try {
                os.close();
            } finally {
                is.close();
            }
        }
        return outputFile;
    }

    protected boolean getRemoteFTPFile() throws IOException {
        try {
            if (ftp == null) {
                ftp = new ftpUtils(getRemoteFTP());
                fileSizeMap = ftpUtils.readRemoteFileList(ftp, getRemoteFTP(), getRemotePath());
            }

            final String remoteFileName = localZipFile.getName();
            final Long fileSize = fileSizeMap.get(remoteFileName);

            final ftpUtils.FTPError result = ftp.retrieveFile(getRemotePath() + remoteFileName, localZipFile, fileSize);
            if (result == ftpUtils.FTPError.OK) {
                return true;
            } else {
                if (result == ftpUtils.FTPError.FILE_NOT_FOUND) {
                    remoteFileExists = false;
                } else {
                    dispose();
                }
                localZipFile.delete();
            }
            return false;
        } catch (SocketException e) {
            unrecoverableError = true;
            throw e;
        } catch (Exception e) {
            System.out.println(e.getMessage());
            if (ftp == null) {
                unrecoverableError = false;      // allow to continue
                remoteFileExists = false;
                throw new IOException("Failed to connect to FTP " + getRemoteFTP() +
                        '\n' + e.getMessage());
            }
            dispose();
        }
        return false;
    }

    protected InputStream getZipInputStream(final File dataFile) throws IOException {
        final String ext = FileUtils.getExtension(dataFile.getName());
        if (ext != null && ext.equalsIgnoreCase(".zip")) {
            final String baseName = localFile.getName();

            ZipFile zipFile = null;
            try {
                zipFile = new ZipFile(dataFile);
                ZipEntry zipEntry = zipFile.getEntry(baseName);
                if (zipEntry == null) {
                    zipEntry = zipFile.getEntry(baseName.toLowerCase());
                    if (zipEntry == null) {
                        final String folderName = FileUtils.getFilenameWithoutExtension(dataFile.getName());
                        zipEntry = zipFile.getEntry(folderName + '/' + localFile.getName());
                        if (zipEntry == null) {
                            localFileExists = false;
                            throw new IOException("Entry '" + baseName + "' not found in zip file.");
                        }
                    }
                }

                return zipFile.getInputStream(zipEntry);
            } finally {
                if (zipFile != null)
                    zipFile.close();
            }
        }
        return null;
    }
}
