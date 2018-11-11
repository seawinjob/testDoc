package com.ce

import com.dc.ExecType
import com.dc.ExerciseRecord
import com.dc.ExerciseRecordService
import com.dc.LessonPageTemplate
import com.dc.ReferenceLessonPage
import com.dc.StartedCourse
import com.dc.StartedLesson
import com.dc.TeacherPageRecord
import com.dc.stats.PracticeSetStats
import com.dc.stats.PracticeSummary
import com.dc.user.User
import com.dc.util.ReferenceLessonUtil
import com.microsoft.schemas.office.visio.x2012.main.PageType

class StatsService implements ReferenceLessonUtil {
    static transactional = false
    ExerciseRecordService exerciseRecordService

    Collection<PracticeSummary> practiceStats(StartedCourse startedCourse) {
        List<ExerciseRecord> rawRecords = exerciseRecordService.listExerciseRecordsForPage(startedCourse)
        Map<User, PracticeSummary> indexedByUser = [:]

        rawRecords.each { record ->
            PracticeSummary stats = indexedByUser.get(record.answerUser)
            if (!stats) {
                stats = new PracticeSummary(
                        practiceCount: 0,
                        successCount: 0,
                        name: record.answerUser.getName(),
                        submissionCount: 0,
                        success: false
                )
                indexedByUser.put(record.answerUser, stats)
            }

            if (record.execType == ExecType.Submission) {
                stats.success = record.correct
                stats.submissionCount += 1
            } else {
                stats.practiceCount += 1
                if (record.correct)
                    stats.successCount++
                else {
                    Integer errCnt = stats.errorClass.get(record.errorClass)
                    if (errCnt) {
                        stats.errorClass[record.errorClass] = (++errCnt)
                    } else {
                        stats.errorClass = [:]
                        stats.errorClass[record.errorClass] = 1
                    }
                }
            }
        }

        Collection<PracticeSummary> summaries = indexedByUser.values()
        summaries
    }

    def lessonStats(Long staredCourseId, Integer lessonNumber) {
        StartedCourse sc = StartedCourse.get(staredCourseId)
        StartedLesson sl = sc.getStartedLessons().find { it.referenceLesson.number == lessonNumber }
        List<ExerciseRecord> rawData = exerciseRecordService.listExerciseRecordsForLesson(sl)
        List<ReferenceLessonPage> pages = getAllPracticeAndExercisePage(sl.getReferenceLesson())

        def totalPracticeCount = pages.inject 0, { sum, next ->
            if (next.type == LessonPageTemplate.PAGE_TYPE_PRACTICE ||
                    next.type == LessonPageTemplate.PAGE_TYPE_EXERCISE)
                sum = sum + 1
            else if (next.type == LessonPageTemplate.PAGE_TYPE_PRACTICE_SET) {
                sum = sum + next.sectionGroups().size()
            }

            sum
        }

        def tryCount = 0
        def successSubmissionCount = 0
        def submissionCount = 0
        def successTryCount = 0
        def preparedData = exerciseRecordService.prepareData(rawData)
        def errorTypes = [:]

        def statsData = preparedData.collect { User student, Map<Integer, List<ExerciseRecord>> dataByExciseNumber ->
            PracticeSetStats ess = new PracticeSetStats(
                    totalCount: totalPracticeCount,
                    stats: new HashMap<Integer, Map>()
            )
            ess.name = student.getName()
            ess.student_id = student.id
            def studentTryCount = 0
            def studentSuccessTryCount = 0
            def studentSubmissionCount = 0
            def studentSuccessSubmissionCount = 0
            def studentErrorTypes = [:]

            (1..totalPracticeCount).each { number ->
                Map stats = new HashMap()
                stats.put('number', number)

                List<ExerciseRecord> erList = dataByExciseNumber.get(number)
                if (erList) {
                    List<ExerciseRecord> submittedRecords = erList.findAll { it.execType == ExecType.Submission }
                    List<ExerciseRecord> successSubmittedRecords = erList.findAll {
                        it.execType == ExecType.Submission && it.correct
                    }

                    if (successSubmittedRecords && successSubmittedRecords.size() > 0) {
                        stats.put('correct', true)
                        studentSuccessSubmissionCount = successSubmittedRecords.size()
                    } else
                        stats.put('correct', false)
                    studentSubmissionCount += submittedRecords.size()
                    stats.put('submissionCount', studentSubmissionCount)

                    erList.each { ExerciseRecord er ->
                        studentTryCount++
                        if (er.correct)
                            studentSuccessTryCount++
                        else {
                            Integer errorTypeCount = errorTypes.get(er.errorClass)

                            if (errorTypeCount) {
                                studentErrorTypes.put(er.errorClass, 1 + errorTypeCount)
                            } else {
                                studentErrorTypes.put(er.errorClass, 1)
                            }
                        }
                    }
                    stats.put('student_id', student.id)
                    stats.put('studentTryCount', studentTryCount)
                    stats.put('failCount', studentTryCount - successTryCount)
                    stats.put('errorTypes', studentErrorTypes)
                    stats.put('studentSubmissionCount', studentSubmissionCount)
                    stats.put('studentSuccessSubmissionCount', studentSuccessSubmissionCount)
                    stats.put('studentFailSubmissionCount', studentSubmissionCount - studentSuccessSubmissionCount)
                } else {
                    [correct: false, retry: 0, success: 0, submissionCount: 0]
                }

                ess.stats.put(number, stats)
                ess.studentTryCount = studentTryCount
                ess.studentSuccessTryCount = studentSuccessTryCount
                ess.studentFailTryCount = studentTryCount - studentSuccessTryCount
                ess.studentSubmissionCount = studentSubmissionCount
                ess.studentSuccessSubmissionCount = studentSuccessSubmissionCount
                ess.studentFailSubmissionCount = studentSubmissionCount - studentSuccessSubmissionCount
            }

            tryCount += studentTryCount
            successTryCount += studentSuccessTryCount
            submissionCount += studentSubmissionCount
            successSubmissionCount += studentSuccessSubmissionCount

            if (studentErrorTypes) {
                studentErrorTypes.each { key, value ->
                    def v = errorTypes.get(key)
                    if (v) v = v + value else v = value
                    errorTypes.put(key, v)
                }
            }
            ess
        }
        [
                practiceCount         : totalPracticeCount,
                studentCount          : preparedData.size(),
                tryCount              : tryCount,
                successCount          : successTryCount,
                failureCount          : tryCount - successTryCount,
                practiceStats         : statsData,
                submissionCount       : submissionCount,
                successSubmissionCount: successSubmissionCount,
                errorTypes            : errorTypes
        ]/*
        def statsDataTest = (0..300).collect {
            def stats = [:]
            (1..6).each {
                def statsItem = [:]
                statsItem.number = it
                statsItem.correct = new Random().nextBoolean()

                stats.put(it, statsItem)
            }

            new PracticeSetStats(
                    name: "test$it",
                    stats: stats,
                    totalCount: 7
            )
        }

        [
                practiceCount         : totalPracticeCount,
                studentCount          : preparedData.size(),
                tryCount              : tryCount,
                successCount          : successTryCount,
                failureCount          : tryCount - successTryCount,
                practiceStats         : statsDataTest,
                submissionCount       : submissionCount,
                successSubmissionCount: successSubmissionCount,
                errorTypes            : errorTypes
        ]
        */

    }

    Collection<PracticeSetStats> getPracticeSetStats(StartedCourse sc,
                                                     Integer pageNumber,
                                                     TeacherPageRecord tpr) {
        ReferenceLessonPage rlp = sc.currentLesson?.referenceLesson?.pages?.find { it.number == pageNumber }

        Integer totalPracticeCount = 0

        if (rlp.type == LessonPageTemplate.PAGE_TYPE_PRACTICE_SET) {
            totalPracticeCount = rlp.sectionGroups().size()
        }

        List<ExerciseRecord> rawRecords = exerciseRecordService.listExerciseRecordsForPage(sc, pageNumber, tpr)
        def preparedData = exerciseRecordService.prepareData(rawRecords)
        preparedData.collect { User student, Map<Integer, List<ExerciseRecord>> dataByExciseNumber ->
            PracticeSetStats ess = new PracticeSetStats(
                    totalCount: totalPracticeCount,
                    stats: new HashMap<Integer, Map>()
            )
            ess.name = student.getName()

            (1..totalPracticeCount).each { number ->
                Map stats = new HashMap()
                stats.put('number', number)

                List<ExerciseRecord> erList = dataByExciseNumber.get(number)
                if (erList) {
                    List<ExerciseRecord> submittedRecords = erList.findAll { it.execType == ExecType.Submission }

                    if (submittedRecords.find { it.correct })
                        stats.put('correct', true)
                    else
                        stats.put('correct', false)
                    stats.put('studentSubmissionCount', submittedRecords.size())

                    Integer tryCount = 0
                    Integer successTryCount = 0
                    erList.each { ExerciseRecord er ->
                        if (er.execType == ExecType.Practice) {
                            tryCount++
                            if (er.correct)
                                successTryCount++
                        }
                    }
                    stats.put('tryingCount', tryCount)
                    stats.put('successCount', successTryCount)
                    stats.put('failCount', tryCount - successTryCount)
                } else {
                    [correct: false, retry: 0, success: 0, submissionCount: 0]
                }

                ess.stats.put(number, stats)
            }

            ess
        }
    }
}
