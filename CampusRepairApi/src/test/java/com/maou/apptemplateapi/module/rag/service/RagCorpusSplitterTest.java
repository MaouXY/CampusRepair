package com.maou.apptemplateapi.module.rag.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语料清洗与章节切分：页码/页眉清理、硬换行合并、标题识别、超长章节二次切分与章节标题前缀。
 */
class RagCorpusSplitterTest {

    private final RagCorpusSplitter splitter = new RagCorpusSplitter();

    @Test
    void shouldRemovePageNumbersAndRepeatedHeaders() {
        String raw = """
                校园设备维保规范
                第一章 总则
                1
                本规范适用于校园内各类设备的日常维护。
                校园设备维保规范
                第二章 空调设备
                - 2 -
                空调滤网应每季度清洗一次。
                校园设备维保规范
                """;

        String cleaned = splitter.clean(raw);

        assertThat(cleaned).doesNotContain("- 2 -").doesNotContain("\n1\n");
        assertThat(cleaned.lines().filter(line -> line.trim().equals("校园设备维保规范")).count())
                .as("重复出现 3 次的页眉应被清理").isZero();
        assertThat(cleaned).contains("第一章 总则").contains("第二章 空调设备");
    }

    @Test
    void shouldMergeHardWrappedLines() {
        String raw = """
                第一章 总则
                本规范适用于校园内各类设备的日常维护
                与检修工作，涉及水电、空调、门窗等
                常见故障处理。
                """;

        String cleaned = splitter.clean(raw);

        assertThat(cleaned).contains("本规范适用于校园内各类设备的日常维护与检修工作，涉及水电、空调、门窗等常见故障处理。");
    }

    @Test
    void shouldSplitByChapterHeadings() {
        String raw = """
                第一章 总则
                本规范用于指导校园设备维护工作，明确责任分工与响应时限。
                第二章 空调设备维护
                空调滤网每季度清洗一次，冷媒压力每半年检测一次。
                2.1 滤网清洗
                断电后取下滤网，用清水冲洗并晾干后装回。
                第三章 电梯设备维护
                电梯维保应由具备资质的单位按周期执行。
                """;

        List<RagCorpusSplitter.CorpusSection> sections = splitter.split(raw, 800, 120);

        assertThat(sections).extracting(RagCorpusSplitter.CorpusSection::sectionTitle)
                .containsExactly("第一章 总则", "第二章 空调设备维护",
                        "第二章 空调设备维护 / 2.1 滤网清洗", "第三章 电梯设备维护");
        assertThat(sections.get(0).content())
                .startsWith("【章节】第一章 总则")
                .contains("明确责任分工与响应时限");
        assertThat(sections.get(1).content()).contains("空调滤网每季度清洗一次");
        assertThat(sections.get(2).sectionLevel()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void shouldChunkLongSectionWithOverlapAndKeepSectionTitle() {
        StringBuilder raw = new StringBuilder("第一章 长章节\n");
        for (int index = 0; index < 40; index++) {
            raw.append("巡检要点%02d：检查外观、线路、接地与运行声音，发现问题立即上报处理并完整记录在案，避免遗漏。\n"
                    .formatted(index + 1));
        }

        List<RagCorpusSplitter.CorpusSection> sections = splitter.split(raw.toString(), 300, 50);

        assertThat(sections).hasSizeGreaterThan(1);
        assertThat(sections).allMatch(section -> section.content().startsWith("【章节】第一章 长章节"));
        assertThat(sections).allMatch(section -> section.sectionTitle().equals("第一章 长章节"));
    }

    @Test
    void shouldKeepBodyTextWrittenOnTheSameLineAsArticleHeading() {
        String raw = """
                第一章 总则
                第一条 为规范校园设备维护工作，明确各部门职责分工与响应时限。
                第二条 设备维护应遵循安全第一、预防为主、节能环保的原则。
                """;

        List<RagCorpusSplitter.CorpusSection> sections = splitter.split(raw, 800, 100);

        assertThat(sections).extracting(RagCorpusSplitter.CorpusSection::sectionTitle)
                .containsExactly("第一章 总则 / 第一条", "第一章 总则 / 第二条");
        assertThat(sections.get(0).content())
                .contains("【章节】第一章 总则 / 第一条")
                .contains("为规范校园设备维护工作");
        assertThat(sections.get(1).content()).contains("安全第一");
    }

    @Test
    void shouldKeepPreambleAsBodySection() {
        String raw = """
                为保障校园设施安全运行，特制定本规范。
                第一章 适用范围
                适用于教学楼、宿舍楼的设备维护。
                """;

        List<RagCorpusSplitter.CorpusSection> sections = splitter.split(raw, 800, 100);

        assertThat(sections.get(0).sectionTitle()).isEqualTo("正文");
        assertThat(sections.get(0).content()).contains("特制定本规范");
        assertThat(sections.get(1).sectionTitle()).isEqualTo("第一章 适用范围");
    }

    @Test
    void shouldReturnEmptyForBlankContent() {
        assertThat(splitter.split(null, 800, 100)).isEmpty();
        assertThat(splitter.split("   \n\n  ", 800, 100)).isEmpty();
    }

    @Test
    void shouldNotTreatLongSentenceAsHeading() {
        String longLine = "1. 本条规定了校园内所有楼宇设备的日常巡检频次、记录方式以及异常上报流程与责任划分要求，请严格执行。";
        String raw = "第一章 总则\n" + longLine;

        List<RagCorpusSplitter.CorpusSection> sections = splitter.split(raw, 800, 100);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).sectionTitle()).isEqualTo("第一章 总则");
        assertThat(sections.get(0).content()).contains("请严格执行");
    }
}
