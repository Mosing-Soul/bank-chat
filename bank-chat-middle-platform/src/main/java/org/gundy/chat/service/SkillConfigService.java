package org.gundy.chat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gundy.chat.entity.config.SkillConfig;
import org.gundy.chat.entity.config.SkillConfigResponse;
import org.gundy.chat.entity.config.SkillExampleConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SkillConfigService {
    private static final int CONFIG_ID = 1;
    private static final double DIRECT_MATCH_THRESHOLD = 0.72D;
    private static final double CANDIDATE_THRESHOLD = 0.28D;

    private final ObjectMapper objectMapper;
    private final String sqlitePath;
    private final boolean persistenceEnabled;
    private List<SkillConfig> skills;

    public SkillConfigService() {
        this.objectMapper = new ObjectMapper();
        this.sqlitePath = null;
        this.persistenceEnabled = false;
        this.skills = buildDefaultSkills();
    }

    public SkillConfigService(ObjectMapper objectMapper,
                              @Value("${bank.skills.config.sqlite-path:data/bank-chat-skill-config.db}") String sqlitePath) {
        this.objectMapper = objectMapper;
        this.sqlitePath = sqlitePath;
        this.persistenceEnabled = true;
        initializeStore();
        this.skills = loadPersistedOrDefault();
    }

    public synchronized SkillConfigResponse getConfig() {
        this.skills = sanitizeSkills(loadPersistedOrCurrent());
        SkillConfigResponse response = new SkillConfigResponse();
        response.setSkills(skills);
        response.setQuickActions(filterExamples(true, false));
        response.setGreetings(filterExamples(false, true));
        return response;
    }

    public synchronized SkillConfigResponse saveConfig(SkillConfigResponse request) {
        List<SkillConfig> nextSkills = request == null ? new ArrayList<SkillConfig>() : request.getSkills();
        if (nextSkills == null || nextSkills.isEmpty()) {
            nextSkills = buildDefaultSkills();
        }
        nextSkills = sanitizeSkills(nextSkills);
        normalizeConfig(nextSkills);
        this.skills = nextSkills;
        persist(nextSkills);
        return getConfig();
    }

    public synchronized SkillConfigResponse resetToDefault() {
        this.skills = buildDefaultSkills();
        persist(this.skills);
        return getConfig();
    }

    public synchronized List<SkillConfig> allSkills() {
        this.skills = sanitizeSkills(loadPersistedOrCurrent());
        return new ArrayList<SkillConfig>(skills);
    }

    public synchronized List<SkillExampleConfig> allExamples() {
        this.skills = loadPersistedOrCurrent();
        List<SkillExampleConfig> examples = new ArrayList<SkillExampleConfig>();
        for (SkillConfig skill : skills) {
            if (skill.isEnabled()) {
                examples.addAll(skill.getExamples());
            }
        }
        Collections.sort(examples, new Comparator<SkillExampleConfig>() {
            @Override
            public int compare(SkillExampleConfig left, SkillExampleConfig right) {
                return Integer.compare(left.getSortOrder(), right.getSortOrder());
            }
        });
        return examples;
    }

    public Map<String, Object> examplesPayload() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
        for (SkillExampleConfig example : allExamples()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("skillCode", example.getSkillCode());
            item.put("text", example.getText());
            item.put("displayText", example.getDisplayText());
            item.put("confidence", example.getConfidence());
            item.put("forceWhenClicked", example.isForceWhenClicked());
            values.add(item);
        }
        payload.put("examples", values);
        return payload;
    }

    public SkillExampleConfig bestExampleMatch(String userMessage) {
        ExampleCandidate best = bestExampleCandidate(userMessage);
        return best != null && best.score >= DIRECT_MATCH_THRESHOLD ? best.example : null;
    }

    public double bestExampleScore(String userMessage, SkillExampleConfig example) {
        if (example == null) {
            return 0.0D;
        }
        return scoreExample(userMessage, example);
    }

    public List<Map<String, Object>> clarificationCandidates(String userMessage, int limit) {
        List<SkillConfig> currentSkills = allSkills();
        Map<String, SkillConfig> skillMap = new LinkedHashMap<String, SkillConfig>();
        for (SkillConfig skill : currentSkills) {
            if (skill.isEnabled() && skill.isFrontendVisible()) {
                skillMap.put(skill.getSkillCode(), skill);
            }
        }

        Map<String, ExampleCandidate> bestBySkill = new LinkedHashMap<String, ExampleCandidate>();
        for (SkillExampleConfig example : allExamples()) {
            SkillConfig skill = skillMap.get(example.getSkillCode());
            if (skill == null || "GENERAL_CHAT".equals(example.getSkillCode())) {
                continue;
            }
            double score = scoreExample(userMessage, example);
            ExampleCandidate existing = bestBySkill.get(example.getSkillCode());
            if (existing == null || score > existing.score) {
                bestBySkill.put(example.getSkillCode(), new ExampleCandidate(example, score));
            }
        }

        List<ExampleCandidate> ranked = new ArrayList<ExampleCandidate>(bestBySkill.values());
        Collections.sort(ranked, new Comparator<ExampleCandidate>() {
            @Override
            public int compare(ExampleCandidate left, ExampleCandidate right) {
                return Double.compare(right.score, left.score);
            }
        });

        List<Map<String, Object>> candidates = new ArrayList<Map<String, Object>>();
        for (ExampleCandidate candidate : ranked) {
            if (candidate.score < CANDIDATE_THRESHOLD || candidates.size() >= limit) {
                continue;
            }
            SkillConfig skill = skillMap.get(candidate.example.getSkillCode());
            candidates.add(candidatePayload(skill, candidate.example, candidate.score));
        }

        if (candidates.isEmpty()) {
            List<SkillConfig> fallback = new ArrayList<SkillConfig>(skillMap.values());
            Collections.sort(fallback, new Comparator<SkillConfig>() {
                @Override
                public int compare(SkillConfig left, SkillConfig right) {
                    return Integer.compare(right.getFallbackPriority(), left.getFallbackPriority());
                }
            });
            for (SkillConfig skill : fallback) {
                if ("GENERAL_CHAT".equals(skill.getSkillCode()) || candidates.size() >= limit) {
                    continue;
                }
                candidates.add(candidatePayload(skill, firstExample(skill), 0.0D));
            }
        }
        return candidates;
    }

    public String skillName(String skillCode) {
        for (SkillConfig skill : allSkills()) {
            if (skill.getSkillCode() != null && skill.getSkillCode().equals(skillCode)) {
                return skill.getSkillName();
            }
        }
        return skillCode;
    }

    private List<SkillExampleConfig> filterExamples(boolean quickAction, boolean greeting) {
        List<SkillExampleConfig> result = new ArrayList<SkillExampleConfig>();
        for (SkillExampleConfig example : allExamples()) {
            if (quickAction && example.isQuickAction()) {
                result.add(example);
            } else if (greeting && example.isGreeting()) {
                result.add(example);
            }
        }
        return result;
    }

    private Map<String, Object> candidatePayload(SkillConfig skill, SkillExampleConfig example, double score) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("requestedSkill", skill.getSkillCode());
        item.put("skillCode", skill.getSkillCode());
        item.put("skillName", skill.getSkillName());
        item.put("label", skill.getSkillName());
        item.put("description", skill.getClarificationText());
        item.put("prompt", example == null ? "" : example.getText());
        item.put("displayText", example == null ? skill.getSkillName() : example.getDisplayText());
        item.put("confidence", Math.round(score * 100.0D) / 100.0D);
        return item;
    }

    private SkillExampleConfig firstExample(SkillConfig skill) {
        return skill.getExamples() == null || skill.getExamples().isEmpty() ? null : skill.getExamples().get(0);
    }

    private ExampleCandidate bestExampleCandidate(String userMessage) {
        ExampleCandidate best = null;
        for (SkillExampleConfig example : allExamples()) {
            double score = scoreExample(userMessage, example);
            if (best == null || score > best.score) {
                best = new ExampleCandidate(example, score);
            }
        }
        return best;
    }

    private double scoreExample(String userMessage, SkillExampleConfig example) {
        return exampleScore(normalize(userMessage), normalize(example.getText())) * example.getConfidence();
    }

    private void initializeStore() {
        if (!persistenceEnabled) {
            return;
        }
        try {
            Path dbPath = Paths.get(sqlitePath);
            Path parent = dbPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Connection connection = connection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS skill_config_snapshot (" +
                        "id INTEGER PRIMARY KEY CHECK (id = 1)," +
                        "payload TEXT NOT NULL," +
                        "updated_at TEXT NOT NULL" +
                        ")");
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize SQLite skill config store", ex);
        }
    }

    private List<SkillConfig> loadPersistedOrDefault() {
        List<SkillConfig> persisted = sanitizeSkills(loadPersisted());
        if (persisted == null || persisted.isEmpty()) {
            List<SkillConfig> defaults = buildDefaultSkills();
            persist(defaults);
            return defaults;
        }
        if (migrateLegacyRedemptionExample(persisted)) {
            persist(persisted);
        }
        return persisted;
    }

    private List<SkillConfig> loadPersistedOrCurrent() {
        List<SkillConfig> persisted = sanitizeSkills(loadPersisted());
        if (persisted != null && migrateLegacyRedemptionExample(persisted)) {
            persist(persisted);
        }
        return persisted == null || persisted.isEmpty() ? skills : persisted;
    }

    private boolean migrateLegacyRedemptionExample(List<SkillConfig> values) {
        boolean changed = false;
        for (SkillConfig skill : values) {
            if ("RAG_QUERY".equals(skill.getSkillCode())
                    && skill.getDescription() != null
                    && skill.getDescription().contains("提前赎回规则")) {
                skill.setDescription("查询行内制度、客户等级规则、反洗钱法规、监管材料等知识。");
                changed = true;
            }
            if (skill.getExamples() == null) {
                continue;
            }
            for (SkillExampleConfig example : skill.getExamples()) {
                if ("ex-rag-1".equals(example.getExampleId())
                        && ("提前赎回规则是什么".equals(example.getText())
                        || "提前赎回规则".equals(example.getText()))) {
                    example.setText("反洗钱法中，临时冻结的最长时限是48小时吗？");
                    example.setDisplayText("反洗钱临时冻结时限");
                    changed = true;
                }
            }
        }
        return changed;
    }

    private List<SkillConfig> loadPersisted() {
        if (!persistenceEnabled) {
            return null;
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("SELECT payload FROM skill_config_snapshot WHERE id = ?")) {
            statement.setInt(1, CONFIG_ID);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return objectMapper.readValue(resultSet.getString("payload"), new TypeReference<List<SkillConfig>>() {});
            }
        } catch (Exception ex) {
            return null;
        }
    }

    private void persist(List<SkillConfig> values) {
        if (!persistenceEnabled) {
            return;
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT OR REPLACE INTO skill_config_snapshot (id, payload, updated_at) VALUES (?, ?, ?)")) {
            statement.setInt(1, CONFIG_ID);
            statement.setString(2, objectMapper.writeValueAsString(values));
            statement.setString(3, Instant.now().toString());
            statement.executeUpdate();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to persist skill config", ex);
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + sqlitePath);
    }

    private void normalizeConfig(List<SkillConfig> values) {
        for (SkillConfig skill : values) {
            if (skill.getExamples() == null) {
                skill.setExamples(new ArrayList<SkillExampleConfig>());
            }
            int index = 0;
            for (SkillExampleConfig example : skill.getExamples()) {
                if (example.getSkillCode() == null || example.getSkillCode().trim().length() == 0) {
                    example.setSkillCode(skill.getSkillCode());
                }
                if (example.getExampleId() == null || example.getExampleId().trim().length() == 0) {
                    example.setExampleId(skill.getSkillCode() + "-example-" + System.currentTimeMillis() + "-" + index);
                }
                if (example.getDisplayText() == null || example.getDisplayText().trim().length() == 0) {
                    example.setDisplayText(example.getText());
                }
                if (example.getConfidence() <= 0.0D) {
                    example.setConfidence(0.8D);
                }
                if (example.getSortOrder() <= 0) {
                    example.setSortOrder(index + 1);
                }
                index++;
            }
        }
    }

    private List<SkillConfig> sanitizeSkills(List<SkillConfig> values) {
        if (values == null) {
            return null;
        }
        List<SkillConfig> result = new ArrayList<SkillConfig>();
        for (SkillConfig skill : values) {
            String code = skill == null ? null : skill.getSkillCode();
            if ("RAG_QUERY".equals(code) || "GOLD_PRICE".equals(code) || "GENERAL_CHAT".equals(code)) {
                result.add(skill);
            }
        }
        return result;
    }

    private List<SkillConfig> buildDefaultSkills() {
        List<SkillConfig> configs = new ArrayList<SkillConfig>();

        SkillConfig gold = new SkillConfig("GOLD_PRICE", "市场价格查询",
                "查询黄金、金价、Au9999等外部实时行情。", true, true, true, 70,
                "查询黄金或贵金属实时行情价格。");
        gold.getExamples().add(example("ex-gold-1", "GOLD_PRICE", "黄金价格是多少", "黄金价格", "gold", 0.92D, true, true, false, true, 20));
        gold.getExamples().add(example("ex-gold-2", "GOLD_PRICE", "现在金价多少", "查询当前金价", "gold", 0.9D, true, false, true, true, 21));
        gold.getExamples().add(example("ex-gold-3", "GOLD_PRICE", "Au9999现在多少钱", "查询Au9999行情", "gold", 0.88D, false, false, false, true, 22));

        SkillConfig rag = new SkillConfig("RAG_QUERY", "行内知识问答",
                "查询行内制度、客户等级规则、反洗钱法规、监管材料等知识。", true, true, true, 75,
                "查询行内规则、制度、产品说明或文档内容。");
        rag.getExamples().add(example("ex-rag-1", "RAG_QUERY",
                "反洗钱法中，临时冻结的最长时限是48小时吗？", "反洗钱临时冻结时限",
                "product", 0.92D, true, true, false, true, 40));
        rag.getExamples().add(example("ex-rag-2", "RAG_QUERY", "招行的客户等级是怎么样的", "查询客户等级规则", "product", 0.91D, true, false, true, true, 41));
        rag.getExamples().add(example("ex-rag-3", "RAG_QUERY", "客户等级规则是什么", "客户等级规则", "product", 0.88D, false, false, false, true, 42));
        rag.getExamples().add(example("ex-rag-4", "RAG_QUERY", "客户等级是怎么划分的", "客户等级划分", "product", 0.86D, false, false, false, true, 43));

        SkillConfig chat = new SkillConfig("GENERAL_CHAT", "通用问答",
                "兜底对话能力，处理不适合具体工具的自然语言问题。", true, false, false, 10,
                "直接由模型回答这个问题。");
        chat.getExamples().add(example("ex-chat-1", "GENERAL_CHAT", "介绍一下你能做什么", "介绍助手能力", "bank", 0.78D, false, false, false, false, 90));

        configs.add(gold);
        configs.add(rag);
        configs.add(chat);
        return configs;
    }

    private SkillExampleConfig example(String id, String skillCode, String text, String displayText, String icon,
                                       double confidence, boolean showOnHome, boolean quickAction, boolean greeting,
                                       boolean forceWhenClicked, int sortOrder) {
        return new SkillExampleConfig(id, skillCode, text, displayText, icon, confidence,
                showOnHome, quickAction, greeting, forceWhenClicked, sortOrder);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\s，。？！、；：,.!?;:]", "").toLowerCase();
    }

    private double exampleScore(String input, String example) {
        if (input.length() == 0 || example.length() == 0) {
            return 0.0D;
        }
        if (input.equals(example)) {
            return 1.0D;
        }
        if (input.contains(example) || example.contains(input)) {
            double min = Math.min(input.length(), example.length());
            double max = Math.max(input.length(), example.length());
            return Math.max(0.8D, min / max);
        }
        int overlap = 0;
        for (int i = 0; i < input.length(); i++) {
            if (example.indexOf(input.charAt(i)) >= 0) {
                overlap++;
            }
        }
        return (double) overlap / (double) Math.max(input.length(), example.length());
    }

    private static class ExampleCandidate {
        private final SkillExampleConfig example;
        private final double score;

        private ExampleCandidate(SkillExampleConfig example, double score) {
            this.example = example;
            this.score = score;
        }
    }
}
