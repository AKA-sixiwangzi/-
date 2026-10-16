package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuri.training.common.*;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import java.io.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;

class AiAndImportTest {
  @AfterEach
  void clear() {
    Current.clear();
  }

  @Test
  void missingAiConfigurationIsExplicitNotFakeAnswer() {
    Current.set(new Actor(7, Set.of("EMPLOYEE")));
    CourseMapper db = mock(CourseMapper.class);
    when(db.materials(2)).thenReturn(List.of(row("id", 1L, "title", "资料", "content", "安全操作规范")));
    AiService service =
        new AiService(
            db,
            mock(CourseService.class),
            mock(ExamMapper.class),
            mock(ExamService.class),
            new ObjectMapper(),
            mock(AiMapper.class),
            mock(PlatformTransactionManager.class),
            "",
            "",
            "");
    assertEquals(503, assertThrows(Problem.class, () -> service.ask(2, "如何操作？")).status);
  }

  @Test
  void officialExamDisablesAiAssistance() {
    Current.set(new Actor(7, Set.of("EMPLOYEE")));
    ExamMapper exams = mock(ExamMapper.class);
    when(exams.takingExam(7)).thenReturn(1);
    AiService service =
        new AiService(
            mock(CourseMapper.class),
            mock(CourseService.class),
            exams,
            mock(ExamService.class),
            new ObjectMapper(),
            mock(AiMapper.class),
            mock(PlatformTransactionManager.class),
            "",
            "",
            "");
    assertEquals(409, assertThrows(Problem.class, () -> service.ask(2, "答案是什么？")).status);
  }

  @Test
  void templateHasExpectedColumnsAndSample() throws Exception {
    ExcelService excel = new ExcelService(mock(ExamService.class), new ObjectMapper());
    try (var w = new XSSFWorkbook(new ByteArrayInputStream(excel.template()))) {
      assertEquals("course_id", w.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
      assertEquals("score", w.getSheetAt(0).getRow(0).getCell(6).getStringCellValue());
      assertEquals("SINGLE", w.getSheetAt(0).getRow(1).getCell(1).getStringCellValue());
    }
  }

  @Test
  void formulaCellsAreRejectedBeforeImport() throws Exception {
    ExcelService excel = new ExcelService(mock(ExamService.class), new ObjectMapper());
    byte[] bytes;
    try (var w = new XSSFWorkbook(new ByteArrayInputStream(excel.template()));
        var out = new ByteArrayOutputStream()) {
      w.getSheetAt(0).getRow(1).getCell(0).setCellFormula("1+1");
      w.write(out);
      bytes = out.toByteArray();
    }
    var file =
        new MockMultipartFile(
            "file",
            "paper.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            bytes);
    assertEquals(400, assertThrows(Problem.class, () -> excel.importPaper("考试", file)).status);
  }
}
