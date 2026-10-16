package com.xuri.training.mapper;

import java.util.*;
import org.apache.ibatis.annotations.*;

public interface ExamMapper {
  @Insert(
      "INSERT INTO"
          + " question(course_id,owner_id,type,stem,options_json,answer,explanation,source,status)"
          + " VALUES(#{course_id},#{owner_id},#{type},#{stem},#{options_json},#{answer},#{explanation},#{source},#{status})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void question(Map<String, Object> q);

  @Select("SELECT * FROM question WHERE id=#{id}")
  Map<String, Object> questionById(long id);

  @Select("SELECT TOP(500) * FROM question WHERE course_id=#{course} ORDER BY id DESC")
  List<Map<String, Object>> questions(long course);

  @Update("UPDATE question SET status='APPROVED' WHERE id=#{id}")
  void approveQuestion(long id);

  @Insert("INSERT INTO paper(title,owner_id) VALUES(#{title},#{owner_id})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void paper(Map<String, Object> p);

  @Select("SELECT * FROM paper WHERE id=#{id}")
  Map<String, Object> paperById(long id);

  @Select("SELECT * FROM paper WITH(UPDLOCK,HOLDLOCK) WHERE id=#{id}")
  Map<String, Object> lockPaper(long id);

  @Select("SELECT TOP(200) * FROM paper WHERE owner_id=#{owner} OR #{admin}=1 ORDER BY id DESC")
  List<Map<String, Object>> papers(@Param("owner") long owner, @Param("admin") boolean admin);

  @Insert(
      "INSERT INTO paper_question(paper_id,question_id,sort_order,score,snapshot_json)"
          + " VALUES(#{paper},#{question},#{order},#{score},#{snapshot})")
  void paperQuestion(Map<String, Object> r);

  @Select("SELECT * FROM paper_question WHERE paper_id=#{id} ORDER BY sort_order")
  List<Map<String, Object>> paperQuestions(long id);

  @Update("UPDATE paper SET published=1 WHERE id=#{id}")
  void publish(long id);

  @Insert(
      "INSERT INTO"
          + " exam(title,paper_id,owner_id,stage_id,start_at,end_at,duration_minutes,pass_score,max_attempts)"
          + " VALUES(#{title},#{paper_id},#{owner_id},#{stage_id},#{start_at},#{end_at},#{duration_minutes},#{pass_score},#{max_attempts})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void exam(Map<String, Object> e);

  @Select("SELECT * FROM exam WHERE id=#{id}")
  Map<String, Object> examById(long id);

  @Insert("INSERT INTO exam_prerequisite(exam_id,course_id) VALUES(#{exam},#{course})")
  void prerequisite(@Param("exam") long exam, @Param("course") long course);

  @Select(
      "SELECT c.id,c.title,c.status,COALESCE(e.completed,0) completed FROM exam_prerequisite p JOIN"
          + " course c ON c.id=p.course_id LEFT JOIN course_enrollment e ON e.course_id=c.id AND"
          + " e.user_id=#{user} WHERE p.exam_id=#{exam}")
  List<Map<String, Object>> prerequisites(@Param("exam") long exam, @Param("user") long user);

  @Insert(
      "IF NOT EXISTS(SELECT 1 FROM exam_member WHERE exam_id=#{exam} AND user_id=#{user}) INSERT"
          + " INTO exam_member(exam_id,user_id) VALUES(#{exam},#{user})")
  void member(@Param("exam") long exam, @Param("user") long user);

  @Select("SELECT COUNT(*) FROM exam_member WHERE exam_id=#{exam} AND user_id=#{user}")
  int eligible(@Param("exam") long exam, @Param("user") long user);

  @Select(
      "SELECT e.* FROM exam e WHERE EXISTS(SELECT 1 FROM exam_member m WHERE m.exam_id=e.id AND"
          + " m.user_id=#{user}) ORDER BY e.start_at")
  List<Map<String, Object>> myExams(long user);

  @Select(
      "SELECT * FROM exam_attempt WHERE exam_id=#{exam} AND user_id=#{user} ORDER BY attempt_no")
  List<Map<String, Object>> attempts(@Param("exam") long exam, @Param("user") long user);

  @Insert(
      "INSERT INTO exam_attempt(id,exam_id,user_id,attempt_no,started_at_ms,deadline_ms)"
          + " VALUES(#{id},#{exam_id},#{user_id},#{attempt_no},#{started_at_ms},#{deadline_ms})")
  void attempt(Map<String, Object> r);

  @Select("SELECT * FROM exam_attempt WHERE id=#{id}")
  Map<String, Object> attemptById(String id);

  @Select("SELECT * FROM exam_attempt WITH(UPDLOCK,HOLDLOCK) WHERE id=#{id}")
  Map<String, Object> lockAttempt(String id);

  @Select("SELECT COUNT(*) FROM exam_attempt WHERE user_id=#{user} AND status='IN_PROGRESS'")
  int takingExam(long user);

  @Insert(
      "IF EXISTS(SELECT 1 FROM exam_answer WHERE attempt_id=#{attempt} AND"
          + " paper_question_id=#{question}) UPDATE exam_answer SET answer=#{answer} WHERE"
          + " attempt_id=#{attempt} AND paper_question_id=#{question} ELSE INSERT INTO"
          + " exam_answer(attempt_id,paper_question_id,answer)"
          + " VALUES(#{attempt},#{question},#{answer})")
  void answer(
      @Param("attempt") String attempt,
      @Param("question") long question,
      @Param("answer") String answer);

  @Select("SELECT * FROM exam_answer WHERE attempt_id=#{id}")
  List<Map<String, Object>> answers(String id);

  @Update(
      "UPDATE exam_answer SET score=#{score},grader_id=#{grader} WHERE attempt_id=#{attempt} AND"
          + " paper_question_id=#{question}")
  int grade(
      @Param("attempt") String attempt,
      @Param("question") long question,
      @Param("score") int score,
      @Param("grader") Long grader);

  @Update(
      "UPDATE exam_attempt SET"
          + " status=#{status},score=#{score},passed=#{passed},submitted_at_ms=COALESCE(submitted_at_ms,#{submitted})"
          + " WHERE id=#{id}")
  void result(Map<String, Object> r);

  @Select("SELECT TOP(100) id FROM exam_attempt WHERE status='IN_PROGRESS' AND deadline_ms<=#{now}")
  List<String> expired(long now);

  @Select(
      "SELECT TOP(200) a.*,e.title FROM exam_attempt a JOIN exam e ON e.id=a.exam_id WHERE"
          + " a.status='PENDING_GRADE' AND (e.owner_id=#{owner} OR #{admin}=1) ORDER BY"
          + " a.started_at_ms")
  List<Map<String, Object>> pending(@Param("owner") long owner, @Param("admin") boolean admin);

  @Select(
      "SELECT q.course_id FROM paper_question p JOIN question q ON q.id=p.question_id WHERE"
          + " p.paper_id=#{paper}")
  Set<Long> paperCourses(long paper);

  @Select(
      "SELECT q.stem,q.explanation,p.score,ans.answer,ans.score AS earned,a.exam_id FROM"
          + " exam_answer ans JOIN paper_question p ON p.id=ans.paper_question_id JOIN question q"
          + " ON q.id=p.question_id JOIN exam_attempt a ON a.id=ans.attempt_id JOIN exam e ON"
          + " e.id=a.exam_id WHERE a.user_id=#{user} AND a.status='GRADED' AND ans.score<p.score"
          + " AND e.end_at<SYSUTCDATETIME() ORDER BY a.started_at_ms DESC")
  List<Map<String, Object>> wrongAnswers(long user);

  @Insert("INSERT INTO exam_event(attempt_id,event_type) VALUES(#{attempt},#{event})")
  void examEvent(@Param("attempt") String attempt, @Param("event") String event);

  @Select("SELECT COUNT(*) FROM exam_event WHERE attempt_id=#{attempt} AND event_type='BLUR'")
  int blurCount(String attempt);
}
