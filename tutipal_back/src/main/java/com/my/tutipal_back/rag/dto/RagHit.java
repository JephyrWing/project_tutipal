package com.my.tutipal_back.rag.dto;

public record RagHit(String filename, Integer page, String content, double similarity) {
    public String source() {
        return page == null ? filename : filename + " p." + page;
    }
}
