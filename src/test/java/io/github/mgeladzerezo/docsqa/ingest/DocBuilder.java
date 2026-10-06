package io.github.mgeladzerezo.docsqa.ingest;

import io.github.mgeladzerezo.docsqa.ingest.ExtractedDocument.Block;
import io.github.mgeladzerezo.docsqa.ingest.ExtractedDocument.BlockType;
import io.github.mgeladzerezo.docsqa.ingest.ExtractedDocument.PageSpan;
import java.util.ArrayList;
import java.util.List;

/** Builds an {@link ExtractedDocument} directly, without going through a PDF. */
final class DocBuilder {

    private final StringBuilder text = new StringBuilder();
    private final List<Block> blocks = new ArrayList<>();
    private final List<PageSpan> pages = new ArrayList<>();
    private int pageStart = -1;

    DocBuilder heading(String heading) {
        return add(BlockType.HEADING, heading);
    }

    DocBuilder paragraph(String paragraph) {
        return add(BlockType.PARAGRAPH, paragraph);
    }

    DocBuilder newPage() {
        closePage();
        return this;
    }

    ExtractedDocument build() {
        closePage();
        return new ExtractedDocument(text.toString(), pages, blocks);
    }

    private DocBuilder add(BlockType type, String content) {
        if (!text.isEmpty()) {
            text.append("\n\n");
        }
        if (pageStart < 0) {
            pageStart = text.length();
        }
        int start = text.length();
        text.append(content);
        blocks.add(new Block(type, start, text.length()));
        return this;
    }

    private void closePage() {
        int number = pages.size() + 1;
        pages.add(pageStart < 0
                ? new PageSpan(number, text.length(), text.length())
                : new PageSpan(number, pageStart, text.length()));
        pageStart = -1;
    }
}
