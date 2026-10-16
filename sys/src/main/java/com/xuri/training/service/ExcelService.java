package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuri.training.common.*;
import java.io.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ExcelService {
  public static final List<String> HEADERS =
      List.of("course_id", "type", "stem", "options_json", "answer", "explanation", "score");
  private final ExamService exams;
  private final ObjectMapper json;

  public ExcelService(ExamService exams, ObjectMapper json) {
    this.exams = exams;
    this.json = json;
  }

  public byte[] template() throws IOException {
    try (Workbook w = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet s = w.createSheet("试题");
      Row h = s.createRow(0);
      for (int i = 0; i < HEADERS.size(); i++) h.createCell(i).setCellValue(HEADERS.get(i));
      Row r = s.createRow(1);
      List<String> example =
          List.of(
              "1",
              "SINGLE",
              "以下哪项符合安全要求？",
              "{\"A\":\"按规程操作\",\"B\":\"跳过检查\"}",
              "A",
              "依据安全操作规范",
              "5");
      for (int i = 0; i < example.size(); i++) {
        r.createCell(i).setCellValue(example.get(i));
        s.setColumnWidth(i, 6000);
      }
      w.write(out);
      return out.toByteArray();
    }
  }

  private String cell(Row r, int i) {
    Cell c = r == null ? null : r.getCell(i);
    Problem.require(c == null || c.getCellType() != CellType.FORMULA, 400, "模板不接受公式单元格");
    return c == null ? "" : new DataFormatter(Locale.ROOT).formatCellValue(c).trim();
  }

  @Transactional
  public Map<String, Object> importPaper(String title, MultipartFile file) throws IOException {
    Problem.require(
        file.getSize() > 0 && file.getSize() <= 5 * 1024 * 1024, 400, "仅支持5MB以内的xlsx试卷模板");
    List<List<String>> rows = new ArrayList<>();
    try (Workbook w = new XSSFWorkbook(file.getInputStream())) {
      Sheet s = w.getSheetAt(0);
      Problem.require(s.getLastRowNum() <= 200, 400, "每份试卷最多200题");
      for (int i = 0; i < HEADERS.size(); i++)
        Problem.require(HEADERS.get(i).equals(cell(s.getRow(0), i)), 400, "表头不符合标准模板");
      for (int n = 1; n <= s.getLastRowNum(); n++) {
        List<String> r = new ArrayList<>();
        for (int i = 0; i < HEADERS.size(); i++) r.add(cell(s.getRow(n), i));
        if (r.stream().allMatch(String::isBlank)) continue;
        rows.add(r);
      }
    }
    List<Long> ids = new ArrayList<>();
    List<Integer> scores = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      List<String> r = rows.get(i);
      try {
        long course = Long.parseLong(r.get(0));
        Map<String, String> options =
            json.readValue(r.get(3), new TypeReference<Map<String, String>>() {});
        Problem.require(
            !r.get(2).isBlank() && r.get(2).length() <= 4000 && r.get(5).length() <= 4000,
            400,
            "题干或解析不合法");
        Map<String, Object> q =
            exams.question(course, r.get(1), r.get(2), options, r.get(4), r.get(5), "IMPORT");
        ids.add(id(q, "id"));
        scores.add(Integer.valueOf(r.get(6)));
      } catch (Exception e) {
        throw new Problem(
            400,
            "第" + (i + 2) + "行导入失败：" + (e instanceof Problem ? e.getMessage() : "课程、JSON或分值格式不正确"));
      }
    }
    return exams.paper(title, ids, scores);
  }
}
