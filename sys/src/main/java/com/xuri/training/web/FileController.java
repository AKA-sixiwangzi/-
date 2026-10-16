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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class FileController {
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
        Set.of(".mp4", ".webm", ".pdf", ".txt").contains(ext),
        400,
        "课程资源支持mp4、webm、pdf、txt；试卷请使用专用xlsx导入入口");
    Files.createDirectories(root);
    String id = UUID.randomUUID().toString(), stored = id + ext;
    Path target = root.resolve(stored);
    String mime =
        switch (ext) {
          case ".mp4" -> "video/mp4";
          case ".webm" -> "video/webm";
          case ".pdf" -> "application/pdf";
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
            default -> file.getSize() <= 2 * 1024 * 1024;
          };
      Problem.require(valid, 400, "文件内容与格式不匹配");
      if (".txt".equals(ext))
        StandardCharsets.UTF_8
            .newDecoder()
            .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(target)));
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
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(str(f, "content_type")))
        .contentLength(Files.size(p))
        .header("X-Content-Type-Options", "nosniff")
        .header("Cache-Control", "private, no-store")
        .header("Content-Security-Policy", "sandbox")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.inline()
                .filename(str(f, "name"), StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(new FileSystemResource(p));
  }
}
