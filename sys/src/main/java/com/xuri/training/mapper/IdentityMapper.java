package com.xuri.training.mapper;

import java.util.*;
import org.apache.ibatis.annotations.*;

public interface IdentityMapper {
  @Select("SELECT * FROM app_user WHERE username=#{name}")
  Map<String, Object> byName(String name);

  @Select("SELECT * FROM app_user WITH(UPDLOCK,HOLDLOCK) WHERE id=#{id}")
  Map<String, Object> lockUser(long id);

  @Select("SELECT role FROM user_role WHERE user_id=#{id}")
  Set<String> roles(long id);

  @Select(
      "SELECT u.id FROM auth_token t JOIN app_user u ON u.id=t.user_id WHERE t.token_hash=#{hash}"
          + " AND t.expires_at>SYSUTCDATETIME() AND u.active=1")
  Long authenticate(String hash);

  @Insert(
      "INSERT INTO auth_token(token_hash,user_id,expires_at)"
          + " VALUES(#{hash},#{userId},DATEADD(hour,8,SYSUTCDATETIME()))")
  void token(@Param("hash") String hash, @Param("userId") long userId);

  @Delete("DELETE FROM auth_token WHERE token_hash=#{hash}")
  void revoke(String hash);

  @Insert(
      "INSERT INTO app_user(username,password_hash,display_name,department_id,position_id,grade_id)"
          + " VALUES(#{username},#{password_hash},#{display_name},#{department_id},#{position_id},#{grade_id})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void create(Map<String, Object> u);

  @Insert(
      "IF NOT EXISTS(SELECT 1 FROM user_role WHERE user_id=#{id} AND role=#{role}) INSERT INTO"
          + " user_role(user_id,role) VALUES(#{id},#{role})")
  void grant(@Param("id") long id, @Param("role") String role);

  @Delete("DELETE FROM user_role WHERE user_id=#{id} AND role=#{role}")
  void ungrant(@Param("id") long id, @Param("role") String role);

  @Delete("DELETE FROM auth_token WHERE user_id=#{id}")
  void revokeAll(long id);

  @Update("UPDATE app_user SET active=#{active} WHERE id=#{id}")
  int active(@Param("id") long id, @Param("active") boolean active);

  @Update("UPDATE app_user SET password_hash=#{hash} WHERE id=#{id}")
  int password(@Param("id") long id, @Param("hash") String hash);

  @Update(
      "UPDATE app_user SET"
          + " display_name=#{display_name},department_id=#{department_id},position_id=#{position_id},grade_id=#{grade_id}"
          + " WHERE id=#{id}")
  int profileUpdate(Map<String, Object> r);

  @Delete("DELETE FROM manager_department WHERE user_id=#{user} AND department_id=#{dept}")
  void revokeScope(@Param("user") long user, @Param("dept") long dept);

  @Select(
      "SELECT id,username,display_name,department_id,position_id,grade_id,active FROM app_user"
          + " ORDER BY id OFFSET #{offset} ROWS FETCH NEXT 100 ROWS ONLY")
  List<Map<String, Object>> users(int offset);

  @Select(
      "SELECT id,username,display_name,department_id,position_id,grade_id,active FROM app_user"
          + " WHERE id=#{id}")
  Map<String, Object> profile(long id);

  @Select("SELECT id FROM app_user WHERE active=1")
  List<Long> activeUsers();

  @Select("SELECT user_id FROM user_role WHERE role='ADMIN'")
  List<Long> admins();

  @Select("SELECT * FROM department ORDER BY id")
  List<Map<String, Object>> departments();

  @Insert("INSERT INTO department(name,parent_id) VALUES(#{name},#{parent_id})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void department(Map<String, Object> r);

  @Select("SELECT * FROM job_position ORDER BY id")
  List<Map<String, Object>> positions();

  @Insert("INSERT INTO job_position(name) VALUES(#{name})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void position(Map<String, Object> r);

  @Select("SELECT * FROM job_grade ORDER BY sort_order,id")
  List<Map<String, Object>> grades();

  @Insert("INSERT INTO job_grade(name,sort_order) VALUES(#{name},#{sort_order})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void grade(Map<String, Object> r);

  @Insert(
      "IF NOT EXISTS(SELECT 1 FROM manager_department WHERE user_id=#{user} AND"
          + " department_id=#{dept}) INSERT INTO manager_department VALUES(#{user},#{dept})")
  void managerScope(@Param("user") long user, @Param("dept") long dept);

  @Select("SELECT * FROM login_guard WITH(UPDLOCK,HOLDLOCK) WHERE username=#{name}")
  Map<String, Object> loginGuard(String name);

  @Insert("INSERT INTO login_guard(username,failures) VALUES(#{name},0)")
  void initGuard(String name);

  @Update(
      "UPDATE login_guard SET failures=CASE WHEN blocked_until IS NOT NULL AND"
          + " blocked_until<=SYSUTCDATETIME() THEN 1 ELSE failures+1 END,blocked_until=CASE WHEN"
          + " blocked_until IS NOT NULL AND blocked_until<=SYSUTCDATETIME() THEN NULL WHEN"
          + " failures>=4 THEN DATEADD(minute,15,SYSUTCDATETIME()) ELSE NULL END WHERE"
          + " username=#{name}")
  void failedLogin(String name);

  @Update("UPDATE login_guard SET failures=0,blocked_until=NULL WHERE username=#{name}")
  void resetGuard(String name);

  @Select(
      "SELECT CASE WHEN blocked_until>SYSUTCDATETIME() THEN 1 ELSE 0 END FROM login_guard WHERE"
          + " username=#{name}")
  Integer blocked(String name);

  @Insert(
      "INSERT INTO audit_log(actor_id,action,target,detail)"
          + " VALUES(#{actor},#{action},#{target},#{detail})")
  void audit(
      @Param("actor") Long actor,
      @Param("action") String action,
      @Param("target") String target,
      @Param("detail") String detail);

  @Select("SELECT TOP(200) * FROM audit_log ORDER BY id DESC")
  List<Map<String, Object>> audits();

  @Delete("DELETE FROM auth_token WHERE expires_at<SYSUTCDATETIME()")
  void expireTokens();

  @Delete("DELETE FROM media_ticket WHERE expires_at<SYSUTCDATETIME()")
  void expireTickets();

  @Insert("INSERT INTO notification(user_id,title,body) VALUES(#{user},#{title},#{body})")
  void notifyUser(
      @Param("user") long user, @Param("title") String title, @Param("body") String body);
}
