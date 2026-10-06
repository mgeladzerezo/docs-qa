package io.github.mgeladzerezo.docsqa.sample;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A sample document described page by page. Because the source says where each page ends, the page a
 * fact is printed on is known by construction, which is what the evaluation's golden set relies on.
 *
 * <p>Source format (see {@code src/main/resources/corpus}): {@code @file} and {@code @header}
 * directives, {@code # title}, {@code ## heading}, paragraphs separated by blank lines and
 * {@code @page} for a page break.
 */
public record CorpusDocument(String fileName, String runningHeader, List<List<Element>> pages) {

    public enum Kind { TITLE, HEADING, PARAGRAPH }

    public record Element(Kind kind, String text) {
    }

    /** The resource names of the bundled sample corpus, in display order. */
    public static final List<String> BUNDLED = List.of(
            "corpus/northwind-travel-policy.txt",
            "corpus/aurora-x2-manual.txt",
            "corpus/kestrel-river-trail.txt",
            "corpus/lumen-db-runbook.txt");

    public static List<CorpusDocument> loadBundled() {
        return BUNDLED.stream().map(CorpusDocument::loadResource).toList();
    }

    public static CorpusDocument loadResource(String resource) {
        try (InputStream in = CorpusDocument.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("No corpus resource " + resource);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static CorpusDocument parse(String source) {
        String fileName = null;
        String header = "";
        List<List<Element>> pages = new ArrayList<>();
        List<Element> page = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        for (String rawLine : source.split("\\R")) {
            String line = rawLine.strip();
            boolean directive = line.startsWith("@") || line.startsWith("#");
            if (line.isEmpty() || directive) {
                if (!paragraph.isEmpty()) {
                    page.add(new Element(Kind.PARAGRAPH, paragraph.toString()));
                    paragraph.setLength(0);
                }
            }
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("@file ")) {
                fileName = line.substring(6).strip();
            } else if (line.startsWith("@header ")) {
                header = line.substring(8).strip();
            } else if (line.equals("@page")) {
                pages.add(page);
                page = new ArrayList<>();
            } else if (line.startsWith("## ")) {
                page.add(new Element(Kind.HEADING, line.substring(3).strip()));
            } else if (line.startsWith("# ")) {
                page.add(new Element(Kind.TITLE, line.substring(2).strip()));
            } else {
                if (!paragraph.isEmpty()) {
                    paragraph.append(' ');
                }
                paragraph.append(line);
            }
        }
        if (!paragraph.isEmpty()) {
            page.add(new Element(Kind.PARAGRAPH, paragraph.toString()));
        }
        pages.add(page);
        if (fileName == null) {
            throw new IllegalArgumentException("Corpus source has no @file directive");
        }
        return new CorpusDocument(fileName, header, pages);
    }
}
