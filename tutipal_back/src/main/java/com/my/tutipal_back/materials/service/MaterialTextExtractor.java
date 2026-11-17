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
    public List<Page> extract(String filename, byte[] data) {
        if (!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf"))
            return List.of(new Page(null, new String(data, StandardCharsets.UTF_8)));
        try (var doc = Loader.loadPDF(data)) {
            if (doc.getNumberOfPages() > 500) throw new IllegalArgumentException("PDF는 500페이지까지 처리합니다");
            var pages = new ArrayList<Page>();
            var stripper = new PDFTextStripper();
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                stripper.setStartPage(i); stripper.setEndPage(i);
                pages.add(new Page(i, stripper.getText(doc)));
            }
            return pages;
        } catch (java.io.IOException e) { throw new IllegalArgumentException("PDF를 읽지 못했어요", e); }
    }
}
