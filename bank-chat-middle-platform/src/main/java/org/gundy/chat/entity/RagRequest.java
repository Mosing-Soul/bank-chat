package org.gundy.chat.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class RagRequest {

    private String question;
    @JsonProperty("session_id")
    private String sessionId;
    private List<HistoryMessage> history;   // 历史对话

}