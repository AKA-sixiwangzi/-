package com.xuri.training.mapper;

import java.util.*;
import org.apache.ibatis.annotations.*;

public interface PlanOperationsMapper {
  @Select("SELECT p.*,u.display_name AS responsible_name FROM training_plan p LEFT JOIN app_user u"
      + " ON u.id=COALESCE(p.responsible_user_id,p.owner_id) WHERE p.id=#{id}")
  Map<String, Object> plan(long id);

  @Select("SELECT * FROM training_plan WITH(UPDLOCK,HOLDLOCK) WHERE id=#{id}")
  Map<String, Object> lockPlan(long id);

  @Update("UPDATE training_plan SET responsible_user_id=#{responsible_user_id},"
      + "remind_before_hours=#{remind_before_hours},remind_every_hours=#{remind_every_hours},"
      + "push_results=#{push_results} WHERE id=#{id}")
  void rules(Map<String, Object> plan);

  @Select("SELECT id FROM training_plan WHERE status='PUBLISHED' ORDER BY id")
  List<Long> publishedPlans();

  @Select("SELECT p.*,u.display_name AS responsible_name FROM training_plan p"
      + " LEFT JOIN app_user u ON u.id=COALESCE(p.responsible_user_id,p.owner_id)"
      + " WHERE #{admin}=1 OR COALESCE(p.responsible_user_id,p.owner_id)=#{actor}"
      + " OR (#{teacher}=1 AND p.status='PUBLISHED' AND EXISTS(SELECT 1 FROM plan_course pc"
      + " JOIN course c ON c.id=pc.course_id WHERE pc.plan_id=p.id AND c.owner_id=#{actor}))"
      + " ORDER BY p.due_at,p.id")
  List<Map<String, Object>> accessiblePlans(@Param("admin") boolean admin, @Param("teacher") boolean teacher,
      @Param("actor") long actor);

  @Select("SELECT c.* FROM plan_course pc JOIN course c ON c.id=pc.course_id"
      + " WHERE pc.plan_id=#{plan} AND (#{full}=1 OR c.owner_id=#{actor}) ORDER BY c.id")
  List<Map<String, Object>> visibleCourses(@Param("plan") long plan,
      @Param("full") boolean full, @Param("actor") long actor);

  @Select("SELECT u.id,u.display_name,u.department_id,u.position_id,u.active FROM plan_member pm"
      + " JOIN app_user u ON u.id=pm.user_id WHERE pm.plan_id=#{plan} ORDER BY u.id")
  List<Map<String, Object>> members(long plan);

  @Select("SELECT m.*,f.name AS file_name,f.content_type FROM course_material m"
      + " LEFT JOIN file_asset f ON f.id=m.asset_id WHERE m.course_id=#{course} ORDER BY m.id")
  List<Map<String, Object>> materials(long course);

  @Select("SELECT e.id,e.title,e.owner_id,e.start_at,e.end_at,e.duration_minutes,e.pass_score"
      + " FROM plan_exam pe JOIN exam e ON e.id=pe.exam_id WHERE pe.plan_id=#{plan}"
      + " AND (#{full}=1 OR e.owner_id=#{actor} OR EXISTS(SELECT 1 FROM exam_prerequisite ep"
      + " JOIN course c ON c.id=ep.course_id WHERE ep.exam_id=e.id AND c.owner_id=#{actor})) ORDER BY e.id")
  List<Map<String, Object>> visiblePlanExams(@Param("plan") long plan,
      @Param("full") boolean full, @Param("actor") long actor);

  @Select("SELECT DISTINCT e.id,e.title,e.start_at,e.end_at,e.duration_minutes,e.pass_score"
      + " FROM plan_exam pe JOIN exam e ON e.id=pe.exam_id"
      + " JOIN exam_prerequisite ep ON ep.exam_id=e.id"
      + " WHERE pe.plan_id=#{plan} AND ep.course_id=#{course} ORDER BY e.id")
  List<Map<String, Object>> courseExams(@Param("plan") long plan, @Param("course") long course);

  @Select("SELECT id,title,instructions FROM assignment WHERE course_id=#{course} ORDER BY id")
  List<Map<String, Object>> assignments(long course);

  @Select("SELECT u.id,u.display_name,u.department_id,u.position_id,COALESCE(ce.completed,0) AS completed,"
      + "ce.completed_at FROM plan_member pm JOIN app_user u ON u.id=pm.user_id"
      + " LEFT JOIN course_enrollment ce ON ce.user_id=u.id AND ce.course_id=#{course}"
      + " WHERE pm.plan_id=#{plan} ORDER BY u.id")
  List<Map<String, Object>> learners(@Param("plan") long plan, @Param("course") long course);

  @Select("SELECT e.id AS exam_id,e.title,last_attempt.id AS attempt_id,"
      + "COALESCE(last_attempt.status,'NOT_STARTED') AS status,last_attempt.score,"
      + "COALESCE(last_attempt.passed,0) AS passed,last_attempt.attempt_no"
      + " FROM plan_exam pe JOIN exam e ON e.id=pe.exam_id"
      + " OUTER APPLY (SELECT TOP(1) a.id,a.status,a.score,a.passed,a.attempt_no"
      + " FROM exam_attempt a WHERE a.exam_id=e.id AND a.user_id=#{user} ORDER BY a.attempt_no DESC) last_attempt"
      + " WHERE pe.plan_id=#{plan} AND EXISTS(SELECT 1 FROM exam_prerequisite ep"
      + " WHERE ep.exam_id=e.id AND ep.course_id=#{course}) ORDER BY e.id")
  List<Map<String, Object>> learnerExams(@Param("plan") long plan,
      @Param("course") long course, @Param("user") long user);

  @Select("SELECT a.id AS assignment_id,a.title,s.id AS submission_id,s.score,s.feedback,"
      + "CASE WHEN s.id IS NULL THEN 'NOT_SUBMITTED' WHEN s.score IS NULL THEN 'SUBMITTED'"
      + " ELSE 'GRADED' END AS status FROM assignment a"
      + " LEFT JOIN assignment_submission s ON s.assignment_id=a.id AND s.user_id=#{user}"
      + " WHERE a.course_id=#{course} ORDER BY a.id")
  List<Map<String, Object>> learnerAssignments(@Param("course") long course, @Param("user") long user);

  @Select("SELECT s.*,u.display_name AS instructor_name FROM offline_session s"
      + " JOIN app_user u ON u.id=s.instructor_id LEFT JOIN course c ON c.id=s.course_id"
      + " WHERE s.plan_id=#{plan} AND (#{full}=1 OR c.owner_id=#{actor} OR s.instructor_id=#{actor})"
      + " ORDER BY s.start_at,s.id")
  List<Map<String, Object>> offlineSessions(@Param("plan") long plan,
      @Param("full") boolean full, @Param("actor") long actor);

  @Select("SELECT u.id,u.display_name FROM plan_member pm JOIN app_user u ON u.id=pm.user_id"
      + " WHERE pm.plan_id=#{plan} AND u.active=1 AND ("
      + " EXISTS(SELECT 1 FROM plan_course pc LEFT JOIN course_enrollment ce"
      + " ON ce.course_id=pc.course_id AND ce.user_id=u.id WHERE pc.plan_id=pm.plan_id"
      + " AND (ce.completed IS NULL OR ce.completed=0))"
      + " OR EXISTS(SELECT 1 FROM plan_exam pe WHERE pe.plan_id=pm.plan_id"
      + " AND NOT EXISTS(SELECT 1 FROM exam_attempt a WHERE a.exam_id=pe.exam_id"
      + " AND a.user_id=u.id AND a.status='GRADED' AND a.passed=1)"
      + " AND NOT EXISTS(SELECT 1 FROM exam_attempt a WHERE a.exam_id=pe.exam_id"
      + " AND a.user_id=u.id AND a.status='PENDING_GRADE'))"
      + " OR EXISTS(SELECT 1 FROM plan_course pc JOIN assignment a ON a.course_id=pc.course_id"
      + " WHERE pc.plan_id=pm.plan_id AND NOT EXISTS(SELECT 1 FROM assignment_submission s"
      + " WHERE s.assignment_id=a.id AND s.user_id=u.id))) ORDER BY u.id")
  List<Map<String, Object>> pendingLearners(long plan);

  @Select("SELECT pm.user_id,pc.course_id,COALESCE(ce.completed,0) AS completed,ce.completed_at"
      + " FROM plan_member pm JOIN plan_course pc ON pc.plan_id=pm.plan_id"
      + " LEFT JOIN course_enrollment ce ON ce.course_id=pc.course_id AND ce.user_id=pm.user_id"
      + " WHERE pm.plan_id=#{plan} ORDER BY pm.user_id,pc.course_id")
  List<Map<String, Object>> courseStates(long plan);

  @Select("SELECT pm.user_id,pe.exam_id,last_attempt.id AS attempt_id,"
      + "COALESCE(last_attempt.status,'NOT_STARTED') AS status,last_attempt.score,"
      + "COALESCE(last_attempt.passed,0) AS passed,last_attempt.attempt_no"
      + " FROM plan_member pm JOIN plan_exam pe ON pe.plan_id=pm.plan_id"
      + " OUTER APPLY (SELECT TOP(1) a.id,a.status,a.score,a.passed,a.attempt_no"
      + " FROM exam_attempt a WHERE a.exam_id=pe.exam_id AND a.user_id=pm.user_id"
      + " ORDER BY a.attempt_no DESC) last_attempt"
      + " WHERE pm.plan_id=#{plan} ORDER BY pm.user_id,pe.exam_id")
  List<Map<String, Object>> examStates(long plan);

  @Select("SELECT pm.user_id,a.id AS assignment_id,s.id AS submission_id,s.score,s.feedback,s.grader_id,"
      + "CASE WHEN s.id IS NULL THEN 'NOT_SUBMITTED' WHEN s.score IS NULL THEN 'SUBMITTED'"
      + " ELSE 'GRADED' END AS status FROM plan_member pm"
      + " JOIN plan_course pc ON pc.plan_id=pm.plan_id JOIN assignment a ON a.course_id=pc.course_id"
      + " LEFT JOIN assignment_submission s ON s.assignment_id=a.id AND s.user_id=pm.user_id"
      + " WHERE pm.plan_id=#{plan} ORDER BY pm.user_id,a.id")
  List<Map<String, Object>> assignmentStates(long plan);

  @Insert("INSERT INTO plan_notice_log(plan_id,recipient_id,kind,state_key)"
      + " SELECT #{plan},#{recipient},#{kind},#{state} WHERE NOT EXISTS"
      + " (SELECT 1 FROM plan_notice_log WITH(UPDLOCK,HOLDLOCK) WHERE plan_id=#{plan}"
      + " AND recipient_id=#{recipient} AND kind=#{kind} AND state_key=#{state})")
  int claimNotice(@Param("plan") long plan, @Param("recipient") long recipient,
      @Param("kind") String kind, @Param("state") String state);
}
