package com.xuri.training.web;

import static com.xuri.training.common.Rows.*;

import com.xuri.training.common.*;
import com.xuri.training.mapper.CourseMapper;
import com.xuri.training.mapper.IdentityMapper;
import com.xuri.training.security.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.poi.poifs.filesystem.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class FileController {
  private static final String PPT = "application/vnd.ms-powerpoint";
  private static final String PPTX =
      "application/vnd.openxmlformats-officedocument.presentationml.presentation";
  private static final long MAX_ZIP_ENTRY = 50L * 1024 * 1024;
  private static final long MAX_ZIP_TOTAL = 200L * 1024 * 1024;
  private static final int MAX_ZIP_ENTRIES = 10000;
  private static final int MAX_PRESENTATION_XML = 2 * 1024 * 1024;
  private final CourseMapper db;
  private final IdentityMapper users;
  private final Path root;

  public FileController(
      CourseMapper db, IdentityMapper users, @Value("${storage.path}") String root) {
    this.db = db;
    this.users = users;
    this.root = Path.of(root).toAbsolutePath().normalize();
  }

  @PostMapping("/files")
  public Object upload(@RequestParam MultipartFile file) throws IOException {
    Problem.require(
        file.getSize() > 0 && file.getSize() <= 500L * 1024 * 1024, 400, "文件大小需在500MB以内");
    String original = Objects.toString(file.getOriginalFilename(), "file").replace('\\', '/');
    String name = original.substring(original.lastIndexOf('/') + 1);
    Problem.require(name.length() <= 200, 400, "文件名过长");
    String ext =
        name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT) : "";
    Problem.require(
        Set.of(".mp4", ".webm", ".pdf", ".ppt", ".pptx", ".txt").contains(ext),
        400,
        "课程资源支持mp4、webm、pdf、ppt、pptx、txt；试卷请使用专用xlsx导入入口");
    Files.createDirectories(root);
    String id = UUID.randomUUID().toString(), stored = id + ext;
    Path target = root.resolve(stored);
    String mime =
        switch (ext) {
          case ".mp4" -> "video/mp4";
          case ".webm" -> "video/webm";
          case ".pdf" -> "application/pdf";
          case ".ppt" -> PPT;
          case ".pptx" -> PPTX;
          default -> "text/plain";
        };
    try {
      file.transferTo(target);
      byte[] prefix;
      try (InputStream in = Files.newInputStream(target)) {
        prefix = in.readNBytes(16);
      }
      boolean valid =
          switch (ext) {
            case ".mp4" ->
                prefix.length >= 12
                    && new String(prefix, 4, 4, StandardCharsets.US_ASCII).equals("ftyp");
            case ".webm" ->
                prefix.length >= 4
                    && (prefix[0] & 255) == 0x1a
                    && (prefix[1] & 255) == 0x45
                    && (prefix[2] & 255) == 0xdf
                    && (prefix[3] & 255) == 0xa3;
            case ".pdf" ->
                prefix.length >= 5
                    && new String(prefix, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-");
            case ".ppt", ".pptx" -> true;
            default -> file.getSize() <= 2 * 1024 * 1024;
          };
      Problem.require(valid, 400, "文件内容与格式不匹配");
      validateDocument(target, ext);
      Map<String, Object> r =
          row(
              "id",
              id,
              "owner_id",
              Current.actor().id(),
              "name",
              name,
              "storage_name",
              stored,
              "content_type",
              mime,
              "size_bytes",
              file.getSize());
      db.file(r);
      r.remove("storage_name");
      return r;
    } catch (Exception e) {
      Files.deleteIfExists(target);
      if (e instanceof Problem p) throw p;
      if (e instanceof IOException i) throw i;
      throw e;
    }
  }

  private void validateDocument(Path target, String extension) {
    try {
      if (".ppt".equals(extension)) {
        try (POIFSFileSystem presentation = new POIFSFileSystem(target.toFile(), true)) {
          DirectoryNode directory = presentation.getRoot();
          Problem.require(directory.hasEntry("PowerPoint Document"), 400, "文件不是有效的PPT演示文稿");
          Entry entry = directory.getEntry("PowerPoint Document");
          Problem.require(entry instanceof DocumentEntry document && document.getSize() >= 8,
              400, "PPT演示文稿内容为空或不完整");
        }
      } else if (".pptx".equals(extension)) {
        validatePresentationZip(target);
      } else if (".txt".equals(extension)) {
        StandardCharsets.UTF_8.newDecoder()
            .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(target)));
      }
    } catch (Problem problem) {
      throw problem;
    } catch (Exception exception) {
      throw new Problem(400, "文件内容与格式不匹配，或演示文稿结构已损坏");
    }
  }

  private void validatePresentationZip(Path target) throws Exception {
    byte[] contentTypes = null, presentation = null;
    long expanded = 0;
    int count = 0;
    Set<String> names = new HashSet<>();
    try (ZipFile archive = new ZipFile(target.toFile())) {
      Enumeration<? extends ZipEntry> entries = archive.entries();
      byte[] buffer = new byte[8192];
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        Problem.require(++count <= MAX_ZIP_ENTRIES, 400, "PPTX文件条目数量过多");
        Problem.require(names.add(entry.getName()), 400, "PPTX文件包含重复条目");
        Problem.require(entry.getSize() <= MAX_ZIP_ENTRY, 400, "PPTX单个资源展开后超过50MB");
        if (entry.isDirectory()) continue;
        boolean required = Set.of("[Content_Types].xml", "ppt/presentation.xml").contains(entry.getName());
        ByteArrayOutputStream xml = required ? new ByteArrayOutputStream() : null;
        long entrySize = 0;
        try (InputStream in = archive.getInputStream(entry)) {
          int read;
          while ((read = in.read(buffer)) != -1) {
            expanded += read;
            entrySize += read;
            Problem.require(expanded <= MAX_ZIP_TOTAL && entrySize <= MAX_ZIP_ENTRY,
                400, "PPTX资源展开后超过大小限制");
            if (required) {
              Problem.require(entrySize <= MAX_PRESENTATION_XML, 400, "PPTX核心文档超过大小限制");
              xml.write(buffer, 0, read);
            }
          }
        }
        if ("[Content_Types].xml".equals(entry.getName())) contentTypes = xml.toByteArray();
        if ("ppt/presentation.xml".equals(entry.getName())) presentation = xml.toByteArray();
      }
    }
    Problem.require(contentTypes != null && presentation != null, 400, "PPTX缺少演示文稿必要内容");
    var types = document(contentTypes);
    Problem.require("Types".equals(types.getDocumentElement().getLocalName())
        && "http://schemas.openxmlformats.org/package/2006/content-types"
            .equals(types.getDocumentElement().getNamespaceURI()), 400, "PPTX内容类型不正确");
    boolean presentationType = false;
    var overrides = types.getElementsByTagNameNS(
        "http://schemas.openxmlformats.org/package/2006/content-types", "Override");
    for (int i = 0; i < overrides.getLength(); i++) {
      var attributes = overrides.item(i).getAttributes();
      var part = attributes.getNamedItem("PartName");
      var type = attributes.getNamedItem("ContentType");
      if (part != null && type != null && "/ppt/presentation.xml".equals(part.getNodeValue())
          && (PPTX + ".main+xml").equals(type.getNodeValue())) presentationType = true;
    }
    var main = document(presentation).getDocumentElement();
    Problem.require(presentationType && "presentation".equals(main.getLocalName())
        && Set.of("http://schemas.openxmlformats.org/presentationml/2006/main",
            "http://purl.oclc.org/ooxml/presentationml/main").contains(main.getNamespaceURI()),
        400, "文件不是有效的PPTX演示文稿");
  }

  private org.w3c.dom.Document document(byte[] xml) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
  }

  private void allowed(String asset, long user, boolean admin) {
    Problem.require(admin || db.readableAsset(asset, user) > 0, 403, "无权访问该文件");
  }

  @PostMapping("/files/{id}/ticket")
  public Object ticket(@PathVariable String id) {
    found(db.asset(id));
    Actor a = Current.actor();
    allowed(id, a.id(), a.admin());
    byte[] b = new byte[32];
    new java.security.SecureRandom().nextBytes(b);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    db.ticket(AuthInterceptor.hash(token), id, a.id());
    return row("url", "/api/media/" + id + "?ticket=" + token, "expiresIn", 900);
  }

  @GetMapping("/media/{id}")
  public ResponseEntity<Resource> media(@PathVariable String id, @RequestParam String ticket)
      throws IOException {
    Problem.require(ticket.length() < 100, 401, "播放凭证不合法");
    Long user = db.mediaUser(AuthInterceptor.hash(ticket), id);
    Problem.require(user != null, 401, "播放凭证过期，请重新获取");
    allowed(id, user, users.roles(user).contains("ADMIN"));
    return resource(found(db.asset(id)));
  }

  @GetMapping("/files/{id}")
  public ResponseEntity<Resource> download(@PathVariable String id) throws IOException {
    Map<String, Object> f = found(db.asset(id));
    Actor a = Current.actor();
    allowed(id, a.id(), a.admin());
    return resource(f);
  }

  private ResponseEntity<Resource> resource(Map<String, Object> f) throws IOException {
    Path p = root.resolve(str(f, "storage_name")).normalize();
    Problem.require(p.startsWith(root) && Files.isRegularFile(p), 404, "文件不存在");
    String mime = str(f, "content_type");
    ContentDisposition.Builder disposition =
        Set.of(PPT, PPTX).contains(mime) ? ContentDisposition.attachment() : ContentDisposition.inline();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(mime))
        .contentLength(Files.size(p))
        .header("X-Content-Type-Options", "nosniff")
        .header("Cache-Control", "private, no-store")
        .header("Content-Security-Policy", "sandbox")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            disposition
                .filename(str(f, "name"), StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(new FileSystemResource(p));
  }
}
