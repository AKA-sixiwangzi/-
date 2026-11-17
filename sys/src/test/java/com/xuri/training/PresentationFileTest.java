package com.xuri.training;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xuri.training.common.Problem;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import com.xuri.training.web.FileController;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class PresentationFileTest {
  static final String PPT = "application/vnd.ms-powerpoint";
  static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
  static final String MAIN = "<p:presentation xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"/>";
  static final String TYPES = "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
      + "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"" + PPTX + ".main+xml\"/>"
      + "</Types>";
  @TempDir Path storage;
  CourseMapper db = mock(CourseMapper.class);
  IdentityMapper users = mock(IdentityMapper.class);
  List<Map<String, Object>> assets = new ArrayList<>();
  FileController controller;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    Current.set(new Actor(7, Set.of("EMPLOYEE")));
    controller = new FileController(db, users, storage.toString());
    doAnswer(call -> {
      assets.add(new HashMap<>((Map<String, Object>) call.getArgument(0)));
      return null;
    }).when(db).file(anyMap());
    when(db.asset(anyString())).thenAnswer(call -> assets.stream()
        .filter(asset -> asset.get("id").equals(call.getArgument(0))).findFirst().orElse(null));
  }

  @AfterEach
  void clear() {
    Current.clear();
  }

  private byte[] ole(String stream, byte[] bytes) throws IOException {
    try (POIFSFileSystem document = new POIFSFileSystem();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      document.createDocument(new ByteArrayInputStream(bytes), stream);
      document.writeFilesystem(output);
      return output.toByteArray();
    }
  }

  private byte[] pptx(Map<String, String> parts) throws IOException {
    try (ByteArrayOutputStream output = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(output)) {
      for (var part : parts.entrySet()) {
        zip.putNextEntry(new ZipEntry(part.getKey()));
        zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      zip.finish();
      return output.toByteArray();
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> upload(String name, byte[] bytes) throws IOException {
    return (Map<String, Object>) controller.upload(
        new MockMultipartFile("file", name, "application/octet-stream", bytes));
  }

  private void assertRejectedAndRemoved(String name, byte[] bytes) throws IOException {
    assertEquals(400, assertThrows(Problem.class, () -> upload(name, bytes)).status);
    verify(db, never()).file(anyMap());
    try (var files = Files.list(storage)) { assertEquals(0, files.count()); }
  }

  @Test
  void acceptsOlePowerPointStreamAndAssignsActualPresentationMime() throws Exception {
    var result = upload("培训课件.ppt", ole("PowerPoint Document", new byte[16]));
    assertEquals(PPT, result.get("content_type"));
    assertEquals("培训课件.ppt", result.get("name"));
    assertFalse(result.containsKey("storage_name"));
    assertTrue(Files.isRegularFile(storage.resolve(assets.get(0).get("storage_name").toString())));
  }

  @Test
  void acceptsPptxOnlyWhenRequiredPresentationPartsAreValid() throws Exception {
    var result = upload("课件.pptx", pptx(Map.of("[Content_Types].xml", TYPES, "ppt/presentation.xml", MAIN)));
    assertEquals(PPTX, result.get("content_type"));
  }

  @Test
  void renamedWordOleDocumentCannotBeUploadedAsPpt() throws Exception {
    assertRejectedAndRemoved("伪装.ppt", ole("WordDocument", new byte[16]));
  }

  @Test
  void emptyPowerPointStreamCannotBeUploaded() throws Exception {
    assertRejectedAndRemoved("空课件.ppt", ole("PowerPoint Document", new byte[0]));
  }

  @Test
  void arbitraryBytesCannotBeUploadedAsPptx() throws Exception {
    assertRejectedAndRemoved("伪装.pptx", "not an Office document".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void ordinaryZipWithoutPresentationPartIsRejected() throws Exception {
    assertRejectedAndRemoved("伪装.pptx", pptx(Map.of("[Content_Types].xml", TYPES, "word/document.xml", MAIN)));
  }

  @Test
  void zipWithWrongMainContentTypeIsRejected() throws Exception {
    assertRejectedAndRemoved("伪装.pptx", pptx(Map.of("[Content_Types].xml",
        TYPES.replace(PPTX + ".main+xml", "application/xml"), "ppt/presentation.xml", MAIN)));
  }

  @Test
  void presentationXmlCannotUseExternalEntities() throws Exception {
    String external = "<!DOCTYPE p:presentation [<!ENTITY local SYSTEM 'file:///etc/passwd'>]>"
        + "<p:presentation xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">&local;</p:presentation>";
    assertRejectedAndRemoved("外部实体.pptx", pptx(Map.of("[Content_Types].xml", TYPES,
        "ppt/presentation.xml", external)));
  }

  @Test
  void compressedOversizedEntryIsRejectedBeforeUnboundedExpansion() throws Exception {
    byte[] bytes;
    try (ByteArrayOutputStream output = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(output)) {
      zip.putNextEntry(new ZipEntry("ppt/media/large.dat"));
      byte[] block = new byte[8192];
      for (int i = 0; i <= 50 * 1024 * 1024 / block.length; i++) zip.write(block);
      zip.closeEntry();
      zip.finish();
      bytes = output.toByteArray();
    }
    assertTrue(bytes.length < 1024 * 1024);
    assertRejectedAndRemoved("过大资源.pptx", bytes);
  }

  @Test
  void malformedUtf8TextReturnsClientErrorAndRemovesUploadedFile() throws Exception {
    assertRejectedAndRemoved("错误编码.txt", new byte[] {(byte) 0xc3, 0x28});
  }

  @Test
  void powerpointDownloadsAsAttachmentButPdfRemainsInline() throws Exception {
    for (String extension : List.of(".ppt", ".pptx", ".pdf")) {
      byte[] bytes = switch (extension) {
        case ".ppt" -> ole("PowerPoint Document", new byte[16]);
        case ".pptx" -> pptx(Map.of("[Content_Types].xml", TYPES, "ppt/presentation.xml", MAIN));
        default -> "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
      };
      var asset = upload("课件" + extension, bytes);
      String id = asset.get("id").toString();
      when(db.readableAsset(id, 7)).thenReturn(1);
      var response = controller.download(id);
      assertEquals(extension.equals(".pdf") ? "inline" : "attachment",
          response.getHeaders().getContentDisposition().getType());
      assertEquals(asset.get("content_type"), response.getHeaders().getContentType().toString());
      assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
    }
  }

  @Test
  void filesAndTicketsRemainUnavailableWithoutCourseOrOwnershipAccess() throws Exception {
    var asset = upload("课件.pptx", pptx(Map.of("[Content_Types].xml", TYPES, "ppt/presentation.xml", MAIN)));
    String id = asset.get("id").toString();
    assertEquals(403, assertThrows(Problem.class, () -> controller.download(id)).status);
    assertEquals(403, assertThrows(Problem.class, () -> controller.ticket(id)).status);
    verify(db, never()).ticket(anyString(), anyString(), anyLong());
  }

  @Test
  void mediaTicketRechecksAccessAndExpiredTicketCannotDownload() throws Exception {
    var asset = upload("课件.pptx", pptx(Map.of("[Content_Types].xml", TYPES, "ppt/presentation.xml", MAIN)));
    String id = asset.get("id").toString();
    when(db.mediaUser(AuthInterceptor.hash("expired"), id)).thenReturn(null);
    assertEquals(401, assertThrows(Problem.class, () -> controller.media(id, "expired")).status);
    when(db.mediaUser(AuthInterceptor.hash("valid"), id)).thenReturn(7L);
    when(users.roles(7)).thenReturn(Set.of("EMPLOYEE"));
    assertEquals(403, assertThrows(Problem.class, () -> controller.media(id, "valid")).status);
    when(db.readableAsset(id, 7)).thenReturn(1);
    assertEquals("attachment", controller.media(id, "valid").getHeaders().getContentDisposition().getType());
  }
}
