package com.xuri.training.mapper;

import java.util.*;
import org.apache.ibatis.annotations.*;

public interface LearningRequestMapper {
  @Select(
      "SELECT TOP(200) r.*,u.display_name AS requester_name,p.title AS path_title,"
          + " reviewer.display_name AS reviewer_name FROM learning_request r"
          + " JOIN app_user u ON u.id=r.requester_id"
          + " LEFT JOIN learning_path p ON p.id=r.path_id"
          + " LEFT JOIN app_user reviewer ON reviewer.id=r.reviewer_id"
          + " WHERE #{admin}=1 OR r.requester_id=#{actor} ORDER BY r.id DESC")
  List<Map<String, Object>> list(@Param("admin") boolean admin, @Param("actor") long actor);

  @Select("SELECT * FROM learning_request WITH(UPDLOCK,HOLDLOCK) WHERE id=#{id}")
  Map<String, Object> lockRequest(long id);

  @Select(
      "SELECT c.id,c.title FROM learning_request_course rc JOIN course c ON c.id=rc.course_id"
          + " WHERE rc.request_id=#{id} ORDER BY c.id")
  List<Map<String, Object>> requestCourses(long id);

  @Select(
      "SELECT u.id,u.display_name,u.department_id,u.position_id FROM learning_request_user ru"
          + " JOIN app_user u ON u.id=ru.user_id WHERE ru.request_id=#{id} ORDER BY u.id")
  List<Map<String, Object>> requestUsers(long id);

  @Insert(
      "INSERT INTO learning_request(requester_id,path_id,reason)"
          + " VALUES(#{requester_id},#{path_id},#{reason})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void create(Map<String, Object> request);

  @Insert("INSERT INTO learning_request_course(request_id,course_id) VALUES(#{request},#{course})")
  void course(@Param("request") long request, @Param("course") long course);

  @Insert("INSERT INTO learning_request_user(request_id,user_id) VALUES(#{request},#{user})")
  void user(@Param("request") long request, @Param("user") long user);

  @Update(
      "UPDATE learning_request SET status=#{status},reviewer_id=#{reviewer},review_note=#{note},"
          + " reviewed_at=SYSUTCDATETIME() WHERE id=#{id} AND status='PENDING'")
  int review(
      @Param("id") long id,
      @Param("reviewer") long reviewer,
      @Param("status") String status,
      @Param("note") String note);

  @Select(
      "SELECT u.id,u.username,u.display_name,u.department_id,u.position_id,u.grade_id,u.active"
          + " FROM app_user u WHERE u.active=1 AND (#{admin}=1 OR EXISTS"
          + " (SELECT 1 FROM manager_department md WHERE md.user_id=#{actor}"
          + " AND md.department_id=u.department_id)) ORDER BY u.id")
  List<Map<String, Object>> eligibleUsers(
      @Param("admin") boolean admin, @Param("actor") long actor);

  @Select("SELECT id,title,category FROM course WHERE status='PUBLISHED' ORDER BY id DESC")
  List<Map<String, Object>> publishedCourses();

  @Select(
      "SELECT p.id,p.title FROM learning_path p WHERE p.published=1 AND EXISTS"
          + " (SELECT 1 FROM path_assignment pa JOIN app_user u ON u.id=pa.user_id"
          + " WHERE pa.path_id=p.id AND u.active=1 AND (#{admin}=1 OR EXISTS"
          + " (SELECT 1 FROM manager_department md WHERE md.user_id=#{actor}"
          + " AND md.department_id=u.department_id))) ORDER BY p.id DESC")
  List<Map<String, Object>> eligiblePaths(
      @Param("admin") boolean admin, @Param("actor") long actor);

  @Select(
      "SELECT u.id FROM path_assignment pa JOIN app_user u ON u.id=pa.user_id"
          + " WHERE pa.path_id=#{path} AND u.active=1 AND (#{admin}=1 OR EXISTS"
          + " (SELECT 1 FROM manager_department md WHERE md.user_id=#{actor}"
          + " AND md.department_id=u.department_id)) ORDER BY u.id")
  List<Long> pathUsers(
      @Param("path") long path, @Param("admin") boolean admin, @Param("actor") long actor);

  @Select(
      "SELECT department_id FROM manager_department WITH(HOLDLOCK) WHERE user_id=#{user}")
  List<Long> managedDepartments(long user);

  @Select("SELECT role FROM user_role WITH(HOLDLOCK) WHERE user_id=#{user}")
  Set<String> requesterRoles(long user);

  @Select("SELECT * FROM learning_path WITH(HOLDLOCK) WHERE id=#{path}")
  Map<String, Object> path(long path);

  @Select(
      "SELECT COUNT(*) FROM path_assignment WITH(HOLDLOCK)"
          + " WHERE path_id=#{path} AND user_id=#{user}")
  int assigned(@Param("path") long path, @Param("user") long user);
}
