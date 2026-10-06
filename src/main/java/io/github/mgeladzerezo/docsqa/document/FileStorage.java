package io.github.mgeladzerezo.docsqa.document;

import io.github.mgeladzerezo.docsqa.config.AppProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Keeps uploaded PDFs on disk under their SHA-256, so identical uploads share one file and a path
 * never contains anything a client typed.
 */
@Component
public class FileStorage {

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

    private final Path directory;

    public FileStorage(AppProperties properties) {
        this.directory = properties.storageDir().toAbsolutePath().normalize();
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create storage directory " + directory, e);
        }
    }

    /**
     * A file received but not yet accepted into the library.
     *
     * @param looksLikePdf whether the content starts with the {@code %PDF-} signature
     */
    public record Received(Path tempFile, String sha256, long size, boolean looksLikePdf) {
    }

    /**
     * Streams the upload to a temporary file while hashing it, and stops as soon as it exceeds
     * {@code maxBytes} so an oversized upload cannot fill the disk.
     *
     * @throws FileTooLargeException if the stream is longer than {@code maxBytes}
     */
    public Received receive(InputStream in, long maxBytes) {
        Path temp = null;
        try {
            temp = Files.createTempFile(directory, "upload-", ".part");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] head = new byte[PDF_MAGIC.length];
            int headLength = 0;
            long size = 0;
            try (OutputStream out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    size += read;
                    if (size > maxBytes) {
                        throw new FileTooLargeException(maxBytes);
                    }
                    for (int i = 0; i < read && headLength < head.length; i++) {
                        head[headLength++] = buffer[i];
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            boolean pdf = headLength == PDF_MAGIC.length && java.util.Arrays.equals(head, PDF_MAGIC);
            return new Received(temp, HexFormat.of().formatHex(digest.digest()), size, pdf);
        } catch (IOException e) {
            discard(temp);
            throw new UncheckedIOException("Could not store the upload", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (RuntimeException e) {
            discard(temp);
            throw e;
        }
    }

    /** Moves an accepted upload to its final name and returns that name. */
    public String commit(Received received) {
        String name = received.sha256() + ".pdf";
        try {
            Files.move(received.tempFile(), directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the upload", e);
        }
        return name;
    }

    public void discard(Received received) {
        discard(received.tempFile());
    }

    public Path resolve(String storagePath) {
        Path path = directory.resolve(storagePath).normalize();
        if (!path.startsWith(directory)) {
            throw new IllegalArgumentException("Storage path escapes the storage directory");
        }
        return path;
    }

    public void delete(String storagePath) {
        discard(resolve(storagePath));
    }

    private static void discard(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // The file is unreferenced either way; a leftover costs disk space, not correctness.
        }
    }

    /** The upload is larger than the configured limit. */
    public static class FileTooLargeException extends RuntimeException {

        public FileTooLargeException(long maxBytes) {
            super("The file is larger than the limit of %d MB.".formatted(maxBytes / (1024 * 1024)));
        }
    }
}
