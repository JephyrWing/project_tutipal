package com.my.tutipal_back.materials.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import com.my.tutipal_back.ai.client.AiClient.Page;

/** PDF/text 추출·청킹·검색은 Spring 안에서만 수행한다. */
@Component
public class MaterialTextExtractor {
    static class InvalidPdf extends IllegalArgumentException {
        InvalidPdf(String message) {
            super(message);
        }

        InvalidPdf(String message, Exception cause) {
            super(message, cause);
        }
    }

    public List<Page> extract(String filename, byte[] data) {
        if (!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf"))
            return List.of(new Page(null, new String(data, StandardCharsets.UTF_8)));
        try (var doc = Loader.loadPDF(data)) {
            if (doc.getNumberOfPages() > 500) throw new InvalidPdf("PDF를 500페이지 이하로 나누어 올려 주세요");
            var pages = new ArrayList<Page>();
            var stripper = new PDFTextStripper();
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                stripper.setStartPage(i); stripper.setEndPage(i);
                pages.add(new Page(i, stripper.getText(doc)));
            }
            return pages;
        } catch (java.io.IOException e) { throw new InvalidPdf("PDF를 읽지 못했어요. 파일을 확인한 뒤 새로 올려 주세요", e); }
    }
}
