package com.maou.apptemplateapi.module.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 语料清洗与章节切分。
 *
 * <p>规范/手册类文档直接按固定字数切分会切断条文语义，因此先按「章/节/条/编号标题」切分，
 * 再对超长章节按自然段二次切分（带重叠），每个切片都会带上章节标题，便于检索后引用出处。
 */
@Slf4j
@Service
public class RagCorpusSplitter {

    private static final int MAX_HEADING_LENGTH = 40;
    private static final int DEFAULT_CHUNK_SIZE = 800;
    private static final int DEFAULT_CHUNK_OVERLAP = 120;
    private static final int MAX_SECTION_CHARS = 1500;

    private static final Pattern PAGE_NUMBER = Pattern.compile("^[-—－\\s]*\\d{1,4}[-—－\\s]*$");
    private static final Pattern PAGE_LABEL = Pattern.compile("^第\\s*\\d{1,4}\\s*页.*$");
    private static final Pattern CHAPTER = Pattern.compile("^第[一二三四五六七八九十百零〇0-9]+[章节条篇].*$");
    private static final Pattern APPENDIX = Pattern.compile("^附\\s*[录件][\\sA-Z0-9一二三四五六七八九十]*.*$");
    private static final Pattern CHINESE_NUMBER_HEADING = Pattern.compile("^[一二三四五六七八九十]+[、.．].*$");
    private static final Pattern BRACKET_NUMBER_HEADING = Pattern.compile("^[（(][一二三四五六七八九十0-9]+[）)].*$");
    private static final Pattern DECIMAL_HEADING = Pattern.compile("^\\d+(\\.\\d+)*[\\s、.．].*$");
    private static final Pattern SENTENCE_END = Pattern.compile(".*[。；：！？）》”\"']$");

    public List<CorpusSection> split(String rawText, Integer chunkSize, Integer chunkOverlap) {
        String cleaned = clean(rawText);
        if (!StringUtils.hasText(cleaned)) {
            return List.of();
        }
        int size = chunkSize == null || chunkSize < 200 ? DEFAULT_CHUNK_SIZE : chunkSize;
        int overlap = chunkOverlap == null || chunkOverlap < 0 ? DEFAULT_CHUNK_OVERLAP : Math.min(chunkOverlap, size / 2);
        List<Section> sections = detectSections(cleaned);
        List<CorpusSection> chunks = new ArrayList<>();
        for (Section section : sections) {
            chunks.addAll(chunkSection(section, size, overlap));
        }
        log.info("rag corpus split done, scenario=rag-corpus-split, rawChars={}, cleanedChars={}, sectionCount={}, chunkCount={}, chunkSize={}, chunkOverlap={}",
                rawText == null ? 0 : rawText.length(), cleaned.length(), sections.size(), chunks.size(), size, overlap);
        return chunks;
    }

    public String clean(String rawText) {
        if (rawText == null) {
            return "";
        }
        String normalized = rawText.replace("\r\n", "\n").replace('\r', '\n').replace('\u3000', ' ')
                .replaceAll("[\\u200b-\\u200f\\ufeff]", "");
        String[] lines = normalized.split("\n", -1);
        Map<String, Integer> lineFrequency = new HashMap<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (StringUtils.hasText(trimmed) && trimmed.length() < MAX_HEADING_LENGTH) {
                lineFrequency.merge(trimmed, 1, Integer::sum);
            }
        }
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (PAGE_NUMBER.matcher(trimmed).matches() || PAGE_LABEL.matcher(trimmed).matches()) {
                continue;
            }
            if (StringUtils.hasText(trimmed) && trimmed.length() < MAX_HEADING_LENGTH
                    && lineFrequency.getOrDefault(trimmed, 0) >= 3 && !isHeading(trimmed)) {
                continue;
            }
            kept.add(trimmed);
        }
        return mergeWrappedLines(kept);
    }

    /**
     * 合并被硬换行切断的段落。
     *
     * <p>关键点：<b>标题行永远不与下一行合并</b>，否则「第一章 总则」会被拼进正文，
     * 后续章节识别就会失效；段落结束（句末标点）或空行之后也一律换行。
     */
    private String mergeWrappedLines(List<String> lines) {
        StringBuilder builder = new StringBuilder();
        boolean previousWasHeading = false;
        boolean previousComplete = true;
        for (String line : lines) {
            if (!StringUtils.hasText(line)) {
                if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
                    builder.append('\n');
                }
                previousWasHeading = false;
                previousComplete = true;
                continue;
            }
            boolean heading = isHeading(line);
            if (builder.length() == 0) {
                builder.append(line);
            } else if (previousWasHeading || previousComplete || builder.charAt(builder.length() - 1) == '\n') {
                if (builder.charAt(builder.length() - 1) != '\n') {
                    builder.append('\n');
                }
                builder.append(line);
            } else {
                char last = builder.charAt(builder.length() - 1);
                builder.append(needsSpace(last, line.charAt(0)) ? " " : "").append(line);
            }
            previousWasHeading = heading;
            previousComplete = SENTENCE_END.matcher(line).matches();
        }
        return builder.toString().trim();
    }

    private boolean needsSpace(char left, char right) {
        return isAscii(left) && isAscii(right);
    }

    private boolean isAscii(char value) {
        return value < 128;
    }

    private List<Section> detectSections(String cleaned) {
        List<Section> sections = new ArrayList<>();
        String currentTitle = "正文";
        int currentLevel = 0;
        String chapterTitle = null;
        int chapterLevel = 0;
        StringBuilder buffer = new StringBuilder();
        for (String line : cleaned.split("\n")) {
            String trimmed = line.trim();
            if (isHeading(trimmed)) {
                if (StringUtils.hasText(buffer.toString())) {
                    sections.add(new Section(qualify(currentTitle, currentLevel, chapterTitle, chapterLevel),
                            currentLevel, buffer.toString().trim()));
                    buffer.setLength(0);
                }
                HeadingParts parts = splitHeading(trimmed);
                currentTitle = parts.title();
                currentLevel = headingLevel(parts.title());
                if (chapterTitle == null || currentLevel <= chapterLevel) {
                    chapterTitle = currentTitle;
                    chapterLevel = currentLevel;
                }
                if (StringUtils.hasText(parts.remainder())) {
                    buffer.append(parts.remainder());
                }
                continue;
            }
            if (StringUtils.hasText(trimmed)) {
                if (buffer.length() > 0) {
                    buffer.append('\n');
                }
                buffer.append(trimmed);
            }
        }
        if (StringUtils.hasText(buffer.toString())) {
            sections.add(new Section(qualify(currentTitle, currentLevel, chapterTitle, chapterLevel),
                    currentLevel, buffer.toString().trim()));
        }
        return sections;
    }

    /**
     * 子章节标题带上上级章名（如「第二章 空调设备维护 / 2.1 滤网清洗」），检索片段自带上下文，引用也更准确。
     */
    private String qualify(String title, int level, String chapterTitle, int chapterLevel) {
        if (chapterTitle == null || title.equals(chapterTitle) || level <= chapterLevel) {
            return title;
        }
        String qualified = chapterTitle + " / " + title;
        return qualified.length() > 190 ? title : qualified;
    }

    /**
     * 标题行可能「编号 + 正文」写在同一行（如「第一条 为规范…」），
     * 这时只把编号部分作为章节标题，其余正文仍然保留，避免丢内容。
     */
    private HeadingParts splitHeading(String line) {
        if (line.length() <= 20) {
            return new HeadingParts(line, null);
        }
        int splitIndex = -1;
        for (int index = 0; index < line.length(); index++) {
            char value = line.charAt(index);
            if (value == ' ' || value == '、' || value == '：' || value == ':' || value == '，' || value == '。') {
                splitIndex = index;
                break;
            }
        }
        if (splitIndex <= 0 || splitIndex >= line.length() - 1) {
            return new HeadingParts(line, null);
        }
        return new HeadingParts(line.substring(0, splitIndex).trim(), line.substring(splitIndex + 1).trim());
    }

    private boolean isHeading(String line) {
        if (!StringUtils.hasText(line) || line.length() > MAX_HEADING_LENGTH) {
            return false;
        }
        return CHAPTER.matcher(line).matches()
                || APPENDIX.matcher(line).matches()
                || CHINESE_NUMBER_HEADING.matcher(line).matches()
                || BRACKET_NUMBER_HEADING.matcher(line).matches()
                || DECIMAL_HEADING.matcher(line).matches();
    }

    private int headingLevel(String line) {
        if (CHAPTER.matcher(line).matches()) {
            return line.contains("章") || line.contains("篇") ? 1 : 2;
        }
        if (APPENDIX.matcher(line).matches()) {
            return 1;
        }
        if (CHINESE_NUMBER_HEADING.matcher(line).matches() || BRACKET_NUMBER_HEADING.matcher(line).matches()) {
            return 2;
        }
        if (DECIMAL_HEADING.matcher(line).matches()) {
            int dots = (int) line.chars().filter(value -> value == '.').count();
            return Math.min(2 + dots, 4);
        }
        return 1;
    }

    private List<CorpusSection> chunkSection(Section section, int chunkSize, int overlap) {
        List<CorpusSection> chunks = new ArrayList<>();
        String content = section.content();
        if (!StringUtils.hasText(content)) {
            return chunks;
        }
        int effectiveSize = Math.min(chunkSize, MAX_SECTION_CHARS);
        if (content.length() <= effectiveSize) {
            chunks.add(new CorpusSection(section.title(), section.level(), render(section.title(), content)));
            return chunks;
        }
        List<String> paragraphs = splitParagraphs(content);
        StringBuilder buffer = new StringBuilder();
        for (String paragraph : paragraphs) {
            if (buffer.length() > 0 && buffer.length() + paragraph.length() > effectiveSize) {
                chunks.add(new CorpusSection(section.title(), section.level(), render(section.title(), buffer.toString())));
                buffer = new StringBuilder(tail(buffer.toString(), overlap));
            }
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(paragraph);
        }
        if (StringUtils.hasText(buffer.toString())) {
            chunks.add(new CorpusSection(section.title(), section.level(), render(section.title(), buffer.toString())));
        }
        return chunks;
    }

    private List<String> splitParagraphs(String content) {
        List<String> paragraphs = new ArrayList<>();
        for (String part : content.split("\n")) {
            if (StringUtils.hasText(part)) {
                paragraphs.add(part.trim());
            }
        }
        return paragraphs.isEmpty() ? List.of(content) : paragraphs;
    }

    private String tail(String value, int overlap) {
        if (overlap <= 0 || value.length() <= overlap) {
            return overlap <= 0 ? "" : value;
        }
        return value.substring(value.length() - overlap);
    }

    private String render(String sectionTitle, String content) {
        return "【章节】%s\n%s".formatted(sectionTitle, content);
    }

    private record Section(String title, int level, String content) {
    }

    private record HeadingParts(String title, String remainder) {
    }

    public record CorpusSection(String sectionTitle, int sectionLevel, String content) {
    }
}
