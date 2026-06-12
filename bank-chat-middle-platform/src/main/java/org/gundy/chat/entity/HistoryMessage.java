package org.gundy.chat.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

// HistoryMessage.java（与 Python 端一致）
@AllArgsConstructor
@Data
public class HistoryMessage {
    private String role;    // "user" 或 "assistant"
    private String content;
    // 构造器、getter、setter 略
}