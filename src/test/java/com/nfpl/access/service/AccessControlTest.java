package com.nfpl.access.service;

import com.nfpl.access.model.Models.*;
import com.nfpl.access.repository.Repo;
import com.nfpl.access.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static com.nfpl.access.repository.Repo.p;
import static org.junit.jupiter.api.Assertions.*;

/**
 * One test per edge case in the brief. Every test starts from the freshly loaded sample data, so the people are the ones
 * described in the README (Asha, Ravi, Meera, Dev, Kiran, Lata, Neha, Priya, the parser ...).
 */
@SpringBootTest
@ActiveProfiles("test")
class AccessControlTest {
    @Autowired SeedLoader seed;
    @Autowired Repo repo;
    @Autowired AccessEvaluator eval;
    @Autowired JobService jobs;
    @Autowired GrantService grants;
    @Autowired ApprovalService approvals;
    @Autowired QueueService queues;
    @Autowired ExplainService explain;

    private int counter = 0;

    @BeforeEach
    void freshData() {
        seed.load("sample");
    }

    // ----------------------------------------------------------------- helpers

    long uid(String username) {
        return repo.jdbc.queryForObject("SELECT id FROM app_user WHERE username = :u", p("u", username), Long.class);
    }

    long job(String jobNo) {
        return repo.jobByNo(jobNo).orElseThrow().id();
    }

    long role(String name) {
        return repo.roleByName(name).orElseThrow().id();
    }

    long newJob(long maker, long client, long branch, String direction, String mode) {
        String no = "T-" + System.nanoTime() + "-" + (counter++);
        jobs.upsert(maker, no, new JobSpec("test job", client, branch, direction, mode));
        return repo.jobByNo(no).orElseThrow().id();
    }

    /** A job that Asha has prepared (so it is waiting for a checker). */
    long preparedJob(long client, long branch, String direction, String mode) {
        long asha = uid("asha");
        long id = newJob(asha, client, branch, direction, mode);
        jobs.perform(asha, "PREPARE", id, null);
        return id;
    }

    ApiException fails(Runnable r) {
        return assertThrows(ApiException.class, r::run);
    }

    List<QueueItem> allQueue(long uid, String filter) {
        List<QueueItem> all = new ArrayList<>();
        LocalDateTime afterTime = null;
        Long afterId = null;
        while (true) {
            QueuePage page = queues.queue(uid, filter, 200, afterTime, afterId);
            if (page.items().isEmpty()) break;
            all.addAll(page.items());
            QueueItem last = page.items().get(page.items().size() - 1);
            afterTime = last.updatedAt();
            afterId = last.id();
        }
        return all;
    }

    // ----------------------------------------------------------------- Q3: maker/checker conflict

    @Test
    @SuppressWarnings("unchecked")
    void makerCannotCheckOwnWork() {
        long asha = uid("asha"), sanjay = uid("sanjay"), dev = uid("dev");
        long job = newJob(asha, 1, 1, "IMPORT", "AIR");          // Asha is a Maker everywhere AND a Checker for C01
        jobs.perform(asha, "PREPARE", job, null);

        ApiException e = fails(() -> jobs.perform(asha, "CHECK", job, null));
        assertEquals("SEPARATION_OF_DUTIES", e.code);
        assertEquals(403, e.status);
        // the reasoning shows she DID have the authority, and that the separation rule is what stopped her
        assertTrue(e.decision.trace().stream().anyMatch(s -> s.check().equals("An access grant covers this job") && s.passed()));

        ActionOutcome done = jobs.perform(sanjay, "CHECK", job, null);
        assertEquals("CHECKED", done.job().stage());
        assertEquals(Long.valueOf(sanjay), done.job().checkedBy());

        // the refused attempt is remembered, with its reason
        List<Map<String, Object>> denied = (List<Map<String, Object>>) explain.history(dev, job).get("deniedAttempts");
        assertEquals(1, denied.size());
        assertEquals("SEPARATION_OF_DUTIES", denied.get(0).get("code"));
    }

    // ----------------------------------------------------------------- Q2: narrow scope (client + branch + direction)

    @Test
    void checkerLimitedToOneClientBranchAndDirection() {
        long ravi = uid("ravi"), farhan = uid("farhan");
        long ok = job("NFPL-NARROW-001"), wrongDirection = job("NFPL-NARROW-002");

        assertTrue(eval.decide(ravi, "CHECK", ok).allowed());
        Decision no = eval.decide(ravi, "CHECK", wrongDirection);
        assertEquals("NO_GRANT", no.code());
        assertTrue(no.message().contains("does not cover this job"), no.message());
        // outside his slice he cannot even see the job, so the service says "not found", not "forbidden"
        assertFalse(eval.canView(ravi, repo.job(wrongDirection).orElseThrow()));
        assertEquals(404, fails(() -> jobs.perform(ravi, "CHECK", wrongDirection, null)).status);

        assertEquals("NO_GRANT", eval.decide(farhan, "CHECK", ok).code());   // Kolkata-only checker
        assertEquals("CHECKED", jobs.perform(ravi, "CHECK", ok, null).job().stage());
    }

    // ----------------------------------------------------------------- Q4: absent owner

    @Test
    void absentOwnerJobsFallIntoThePoolAndCanBeCoveredOrReassigned() {
        long meera = uid("meera"), asha = uid("asha"), divya = uid("divya"), kiran = uid("kiran");
        long job = job("NFPL-ABSENT-001");

        assertEquals("USER_NOT_ACTIVE", eval.decide(meera, "PREPARE", job).code());
        assertEquals(0L, queues.queue(meera, "all", 50, null, null).count());          // absent people have no queue

        QueueItem item = allQueue(asha, "pool").stream().filter(i -> i.id() == job).findFirst().orElseThrow();
        assertEquals("ABSENT", item.ownerStatus());                                     // visible to capable colleagues
        assertEquals("PREPARE", item.nextAction());

        // a supervisor reassigns it; the new owner must be someone who can see the job
        jobs.perform(kiran, "REASSIGN", job, new ActionRequest(null, null, null, divya));
        assertEquals(Long.valueOf(divya), repo.job(job).orElseThrow().ownerId());
        assertTrue(allQueue(divya, "mine").stream().anyMatch(i -> i.id() == job));
        assertEquals("INVALID_OWNER", fails(() -> jobs.perform(kiran, "REASSIGN", job,
                new ActionRequest(null, null, null, uid("priya")))).code);

        // Divya has a temporary cover grant for Delhi, so she can do the work
        assertEquals("PREPARED", jobs.perform(divya, "PREPARE", job, null).job().stage());
    }

    // ----------------------------------------------------------------- Q5: responsibilities change mid-flight

    @Test
    void revokingAGrantReleasesTheJobsTheyCanNoLongerSee() {
        long dev = uid("dev"), vikram = uid("vikram");
        long chennaiGrant = repo.jdbc.queryForObject("SELECT id FROM access_grant WHERE user_id = :u AND branch_id = 3",
                p("u", vikram), Long.class);
        long chennaiJob = repo.jdbc.queryForObject("SELECT id FROM job WHERE owner_id = :u AND branch_id = 3 AND stage IN "
                + "('CONFIRMED','PREPARED','CHECKED','REOPENED') ORDER BY id LIMIT 1", p("u", vikram), Long.class);
        long kolkataJob = repo.jdbc.queryForObject("SELECT id FROM job WHERE owner_id = :u AND branch_id = 4 AND stage IN "
                + "('CONFIRMED','PREPARED','CHECKED','REOPENED') ORDER BY id LIMIT 1", p("u", vikram), Long.class);

        Map<String, Object> result = grants.revoke(dev, chennaiGrant, "Now Kolkata only");
        assertTrue(((Number) result.get("jobsReleasedToPool")).intValue() >= 1);

        assertNull(repo.job(chennaiJob).orElseThrow().ownerId());                       // released to the pool
        assertEquals(Long.valueOf(vikram), repo.job(kolkataJob).orElseThrow().ownerId()); // still his: grant still covers it
        assertFalse(eval.canView(vikram, repo.job(chennaiJob).orElseThrow()));          // and authority is gone immediately
        Integer events = repo.jdbc.queryForObject("SELECT COUNT(*) FROM job_event WHERE job_id = :j AND event_type = 'OWNER_RELEASED'",
                p("j", chennaiJob), Integer.class);
        assertEquals(1, events);                                                         // and the history says so
    }

    // ----------------------------------------------------------------- Q6: cancel a filed job

    @Test
    void cancellingAFiledJobNeedsSupervisorReasonAndASecondPerson() {
        long asha = uid("asha"), kiran = uid("kiran"), lata = uid("lata"), dev = uid("dev");
        long job = repo.jdbc.queryForObject("SELECT id FROM job WHERE stage = 'FILED' AND job_no <> 'NFPL-STORY-001' ORDER BY id LIMIT 1",
                p(), Long.class);
        String why = "Client withdrew the shipment; customs informed";

        assertEquals("NO_GRANT", fails(() -> jobs.perform(asha, "CANCEL_FILED", job, new ActionRequest(why, null, null, null))).code);
        assertEquals("REASON_REQUIRED", fails(() -> jobs.perform(kiran, "CANCEL_FILED", job, null)).code);

        ActionOutcome pending = jobs.perform(kiran, "CANCEL_FILED", job, new ActionRequest(why, null, null, null));
        assertEquals("PENDING_APPROVAL", pending.status());
        assertEquals("FILED", repo.job(job).orElseThrow().stage());                     // nothing happened yet
        long approvalId = pending.approvalId();

        assertEquals("SELF_APPROVAL", fails(() -> approvals.approve(kiran, approvalId)).code);
        assertEquals("NOT_AN_APPROVER", fails(() -> approvals.approve(dev, approvalId)).code);   // admins do not approve job work
        assertEquals("APPROVAL_NOT_APPROVED", fails(() -> jobs.perform(kiran, "CANCEL_FILED", job,
                new ActionRequest(why, approvalId, null, null))).code);

        approvals.approve(lata, approvalId);
        ActionOutcome done = jobs.perform(kiran, "CANCEL_FILED", job, new ActionRequest(why, approvalId, null, null));
        assertEquals("CANCELLED", done.job().stage());
        fails(() -> jobs.perform(kiran, "CANCEL_FILED", job, new ActionRequest(why, approvalId, null, null)));  // approval is one-time
    }

    @Test
    void reopeningAClosedJobNeedsTheSameCareThenReturnsItToTheMakers() {
        long asha = uid("asha"), kiran = uid("kiran"), lata = uid("lata");
        long job = repo.jdbc.queryForObject("SELECT id FROM job WHERE stage = 'CANCELLED' ORDER BY id LIMIT 1", p(), Long.class);
        String why = "Client re-confirmed the order";

        assertEquals("NO_GRANT", fails(() -> jobs.perform(asha, "REOPEN", job, new ActionRequest(why, null, null, null))).code);
        ActionOutcome pending = jobs.perform(kiran, "REOPEN", job, new ActionRequest(why, null, null, null));
        approvals.approve(lata, pending.approvalId());
        ActionOutcome done = jobs.perform(kiran, "REOPEN", job, new ActionRequest(why, pending.approvalId(), null, null));
        assertEquals("REOPENED", done.job().stage());
        assertTrue(eval.decide(asha, "PREPARE", job).allowed());                         // back in the makers' queue
    }

    // ----------------------------------------------------------------- Q8: admins

    @Test
    void adminCannotMakeThemselvesMorePowerful() {
        long dev = uid("dev"), kiran = uid("kiran"), nikhil = uid("nikhil"), bhavna = uid("bhavna");
        long supervisor = role("SUPERVISOR"), checker = role("CHECKER"), admin = role("ADMIN");

        // 1. nobody changes their own access
        assertEquals("SELF_GRANT", fails(() -> grants.grant(dev, new GrantRequest(dev, supervisor, null, null, null, null, null, "me"))).code);

        // 2. a sensitive role for someone else needs a second person, who is not the requester and holds APPROVE_SENSITIVE
        GrantOutcome pending = grants.grant(dev, new GrantRequest(nikhil, supervisor, null, null, null, null, null, "cover"));
        assertEquals("PENDING_APPROVAL", pending.status());
        assertEquals(0, countGrants(nikhil, supervisor));
        assertEquals("SELF_APPROVAL", fails(() -> approvals.approve(dev, pending.approvalId())).code);
        assertEquals("NOT_AN_APPROVER", fails(() -> approvals.approve(bhavna, pending.approvalId())).code);
        approvals.approve(kiran, pending.approvalId());
        assertEquals(1, countGrants(nikhil, supervisor));

        // 3. a branch admin can only hand out access inside her own branch
        assertEquals("OUTSIDE_MANAGE_SCOPE", fails(() -> grants.grant(bhavna,
                new GrantRequest(nikhil, checker, null, 4L, null, null, null, "Kolkata"))).code);
        assertEquals("OUTSIDE_MANAGE_SCOPE", fails(() -> grants.grant(bhavna,
                new GrantRequest(nikhil, checker, null, null, null, null, null, "everywhere"))).code);
        assertEquals("GRANTED", grants.grant(bhavna, new GrantRequest(nikhil, checker, null, 2L, null, null, null, "Delhi")).status());

        // 4. roles: the sensitive ones are fixed, sensitive permissions cannot be added, branch admins cannot manage roles
        assertEquals("SENSITIVE_ROLE_FIXED", fails(() -> grants.setRolePermissions(dev, admin, List.of("JOB_VIEW"))).code);
        assertEquals("SENSITIVE_PERMISSION_NOT_ALLOWED_VIA_API", fails(() -> grants.setRolePermissions(dev, checker,
                List.of("JOB_CHECK", "ACCESS_MANAGE"))).code);
        assertEquals("SENSITIVE_PERMISSION_NOT_ALLOWED_VIA_API", fails(() -> grants.createRole(dev,
                new RoleRequest("SUPER2", "x", List.of("STAFF"), List.of("JOB_REOPEN")))).code);
        assertEquals("NOT_ALLOWED", fails(() -> grants.createRole(bhavna,
                new RoleRequest("BRANCH_ROLE", "x", null, List.of("JOB_VIEW")))).code);

        // 5. nobody edits a role they hold (simulate another admin having given Dev the CHECKER role)
        repo.insertGrant(dev, checker, null, null, null, null, Repo.now().minusDays(1), null, null, null, "test");
        assertEquals("CANNOT_EDIT_OWN_ROLE", fails(() -> grants.setRolePermissions(dev, checker, List.of("JOB_VIEW", "JOB_CHECK"))).code);
    }

    private int countGrants(long userId, long roleId) {
        return repo.jdbc.queryForObject("SELECT COUNT(*) FROM access_grant WHERE user_id = :u AND role_id = :r AND revoked_at IS NULL",
                p("u", userId, "r", roleId), Integer.class);
    }

    @Test
    void adminCanUnstickAJobButDoesNotDoJobWork() {
        long dev = uid("dev"), asha = uid("asha");
        long job = preparedJob(1, 1, "IMPORT", "AIR");

        assertEquals("REASON_REQUIRED", fails(() -> jobs.perform(dev, "ADMIN_STEP_BACK", job, null)).code);
        ActionOutcome o = jobs.perform(dev, "ADMIN_STEP_BACK", job, new ActionRequest("Wrong consignee, preparer on leave", null, null, null));
        assertEquals("CONFIRMED", o.job().stage());
        assertNull(o.job().preparedBy());

        assertEquals("NO_GRANT", fails(() -> jobs.perform(dev, "PREPARE", job, null)).code);    // admins do not prepare/check/file
        jobs.perform(asha, "PREPARE", job, null);
        assertEquals("NO_GRANT", fails(() -> jobs.perform(asha, "ADMIN_STEP_BACK", job,
                new ActionRequest("trying", null, null, null))).code);                           // makers have no override
        assertEquals("WRONG_STAGE", fails(() -> jobs.perform(dev, "ADMIN_STEP_BACK", job("NFPL-STORY-001"),
                new ActionRequest("x", null, null, null))).code);                                // filed jobs are out of reach
    }

    // ----------------------------------------------------------------- Q9: the overnight parser

    @Test
    @SuppressWarnings("unchecked")
    void parserCanCreateAndUpdateUnprocessedJobsButNothingElse() {
        long parser = uid("overnight-parser"), asha = uid("asha"), dev = uid("dev");
        ActionOutcome created = jobs.upsert(parser, "PARSE-1", new JobSpec("from email", 1L, 1L, "IMPORT", "AIR"));
        long id = created.job().id();
        assertEquals("CONFIRMED", created.job().stage());

        ActionOutcome again = jobs.upsert(parser, "PARSE-1", new JobSpec("from email, corrected", 1L, 1L, "IMPORT", "AIR"));
        assertEquals("from email, corrected", again.job().title());                       // safe to re-run overnight
        assertEquals("SCOPE_IMMUTABLE", fails(() -> jobs.upsert(parser, "PARSE-1", new JobSpec("x", 2L, 1L, "IMPORT", "AIR"))).code);

        List<Map<String, Object>> events = (List<Map<String, Object>>) explain.history(dev, id).get("events");
        assertEquals("SYSTEM", events.get(0).get("actor_type"));                           // history shows it was automated

        assertEquals("NO_GRANT", fails(() -> jobs.perform(parser, "PREPARE", id, null)).code);   // never a maker ...
        jobs.perform(asha, "PREPARE", id, null);
        assertEquals("NO_GRANT", fails(() -> jobs.upsert(parser, "PARSE-1", new JobSpec("late edit", 1L, 1L, "IMPORT", "AIR"))).code);
        assertEquals(0L, queues.queue(parser, "all", 50, null, null).count());             // ... and nothing waits for it
        assertEquals("ROLE_NOT_ALLOWED_FOR_USER_TYPE", fails(() -> grants.grant(dev,
                new GrantRequest(parser, role("MAKER"), null, null, null, null, null, "no"))).code);
    }

    // ----------------------------------------------------------------- Q1/Q7: seeing vs acting, client-side users

    @Test
    void clientUserSeesOnlyTheirOwnShipmentsAndCannotAct() {
        long priya = uid("priya"), dev = uid("dev");
        List<QueueItem> visible = queues.visible(priya, null, 200);
        assertFalse(visible.isEmpty());
        assertTrue(visible.stream().allMatch(i -> i.clientId() == 5));
        assertFalse(eval.canView(priya, repo.jobByNo("NFPL-STORY-001").orElseThrow()));   // another client's job
        // a client sees their shipments, but not which internal staff handle them
        assertTrue(visible.stream().allMatch(i -> i.ownerId() == null && i.ownerName() == null && i.nextAction() == null));
        long ownedC5 = repo.jdbc.queryForObject("SELECT id FROM job WHERE client_id = 5 AND prepared_by IS NOT NULL ORDER BY id LIMIT 1",
                p(), Long.class);
        Job seenByClient = jobs.get(priya, ownedC5);
        assertNull(seenByClient.preparedBy());
        assertNull(seenByClient.ownerId());
        assertNotNull(jobs.get(dev, ownedC5).preparedBy());                               // staff still see everything
        assertEquals(404, fails(() -> jobs.get(priya, job("NFPL-STORY-001"))).status);    // other clients' jobs do not exist for her

        long confirmed = repo.jdbc.queryForObject("SELECT id FROM job WHERE client_id = 5 AND stage = 'CONFIRMED' ORDER BY id LIMIT 1",
                p(), Long.class);
        assertEquals("NO_GRANT", fails(() -> jobs.perform(priya, "PREPARE", confirmed, null)).code);   // can see, cannot act
        assertEquals(404, fails(() -> explain.history(priya, confirmed)).status);                      // internal history is staff-only
        assertEquals(0L, queues.queue(priya, "all", 50, null, null).count());

        assertEquals("ROLE_NOT_ALLOWED_FOR_USER_TYPE", fails(() -> grants.grant(dev,
                new GrantRequest(priya, role("MAKER"), 5L, null, null, null, null, "no"))).code);
        assertEquals("CLIENT_USER_MUST_BE_SCOPED_TO_OWN_CLIENT", fails(() -> grants.grant(dev,
                new GrantRequest(priya, role("CLIENT_VIEWER"), 6L, null, null, null, null, "no"))).code);
    }

    // ----------------------------------------------------------------- Q13: the count equals what they can actually do

    @Test
    void jobsWaitingForMeIsExactlyWhatDecideAllows() {
        Map<String, String> nextAction = new HashMap<>();
        for (Map<String, Object> r : repo.jdbc.queryForList("SELECT from_stage, action FROM workflow_transition WHERE is_queue_step = 1", p())) {
            nextAction.put((String) r.get("from_stage"), (String) r.get("action"));
        }
        List<Job> allJobs = repo.jdbc.query("SELECT * FROM job", p(), Repo.JOB);
        LocalDateTime now = Repo.now();
        for (Long u : repo.jdbc.queryForList("SELECT id FROM app_user ORDER BY id", p(), Long.class)) {
            Set<Long> expected = new HashSet<>();
            for (Job j : allJobs) {
                String action = nextAction.get(j.stage());
                if (action != null && eval.decide(u, action, j, now).allowed()) expected.add(j.id());
            }
            Set<Long> queue = allQueue(u, "all").stream().map(QueueItem::id).collect(Collectors.toSet());
            assertEquals(expected, queue, "queue and decide() disagree for user " + repo.userName(u));
            assertEquals(expected.size(), queues.queue(u, "all", 1, null, null).count(), "count for " + repo.userName(u));
        }
    }

    // ----------------------------------------------------------------- Q10: why she can and he can't

    @Test
    void compareExplainsWhyOnePersonCanAndAnotherCannot() {
        long neha = uid("neha"), ravi = uid("ravi"), farhan = uid("farhan"), sanjay = uid("sanjay");
        String s1 = (String) explain.compare(neha, ravi, farhan, "CHECK", job("NFPL-NARROW-001")).get("summary");
        assertTrue(s1.startsWith("Ravi Kulkarni can CHECK"), s1);
        assertTrue(s1.contains("Farhan Khan cannot"), s1);

        String s2 = (String) explain.compare(neha, ravi, sanjay, "CHECK", job("NFPL-NARROW-002")).get("summary");
        assertTrue(s2.startsWith("Sanjay Patil can CHECK"), s2);
        assertTrue(s2.contains("Ravi Kulkarni cannot") && s2.contains("does not cover this job"), s2);

        assertEquals(404, fails(() -> explain.compare(uid("priya"), ravi, farhan, "CHECK", job("NFPL-NARROW-001"))).status);
    }

    // ----------------------------------------------------------------- Q11: who could have changed this job on the 3rd

    @Test
    @SuppressWarnings("unchecked")
    void whoCouldReplaysThePastNotThePresent() {
        long neha = uid("neha"), dev = uid("dev");
        long story = job("NFPL-STORY-001");   // prepared by Asha 24 days ago, checked by Tara 22 days ago, filed 21 days ago

        Map<String, Object> then = explain.whoCould(neha, story, "CHECK", Repo.now().minusDays(23));
        assertEquals("PREPARED", then.get("stageAtThatTime"));
        List<String> could = ((List<Map<String, Object>>) then.get("couldAct")).stream().map(m -> (String) m.get("user")).toList();
        assertTrue(could.contains("Tara Nair"), could.toString());       // her grant was still in force
        assertTrue(could.contains("Sanjay Patil"), could.toString());
        assertFalse(could.contains("Asha Menon"), could.toString());     // she prepared it
        assertFalse(could.contains("Ravi Kulkarni"), could.toString());  // wrong client
        List<Map<String, Object>> excluded = (List<Map<String, Object>>) then.get("excluded");
        assertTrue(excluded.stream().anyMatch(m -> "Asha Menon".equals(m.get("user"))
                && m.get("excludedBecause").toString().contains("maker/checker")));

        Map<String, Object> today = explain.whoCould(neha, story, "CHECK", null);
        assertTrue(today.get("message").toString().contains("not possible while the job was FILED"));   // filed jobs are frozen
        assertTrue(explain.whoCould(neha, story, "CHECK", Repo.now().minusDays(30)).get("message").toString().contains("did not exist"));

        // and today Tara has no access any more
        assertEquals("NO_GRANT", eval.decide(uid("tara"), "CHECK", job("NFPL-SOD-001")).code());
        List<String> lines = (List<String>) explain.describeAccess(dev, uid("tara"), true).get("lines");
        assertTrue(lines.get(0).contains("ENDED"), lines.get(0));
    }

    // ----------------------------------------------------------------- Q12: a new role, no engineering

    @Test
    void aNewRoleTakesEffectImmediatelyAndNarrowingItAlsoDoes() {
        long dev = uid("dev"), nikhil = uid("nikhil");
        long roleId = ((Number) grants.createRole(dev, new RoleRequest("TRAINEE_CHECKER", "Checks C01 Mumbai while training",
                List.of("STAFF"), List.of("JOB_VIEW", "JOB_CHECK", "HISTORY_VIEW"))).get("roleId")).longValue();
        long job = preparedJob(1, 1, "EXPORT", "AIR");

        assertEquals("NO_GRANT", eval.decide(nikhil, "CHECK", job).code());                // a new joiner, no access
        GrantOutcome g = grants.grant(dev, new GrantRequest(nikhil, roleId, 1L, 1L, null, null, null, "training"));
        assertEquals("GRANTED", g.status());
        assertTrue(eval.decide(nikhil, "CHECK", job).allowed());                            // immediately
        assertTrue(allQueue(nikhil, "all").stream().anyMatch(i -> i.id() == job));

        grants.setRolePermissions(dev, roleId, List.of("JOB_VIEW", "HISTORY_VIEW"));         // narrow the role
        assertFalse(eval.decide(nikhil, "CHECK", job).allowed());
        assertTrue(allQueue(nikhil, "all").stream().noneMatch(i -> i.id() == job));

        grants.revoke(dev, g.grantId(), "training over");
        assertFalse(eval.canView(nikhil, repo.job(job).orElseThrow()));
    }

    // ----------------------------------------------------------------- Q14: when unsure, say no

    @Test
    void whenUnsureTheAnswerIsNo() {
        long asha = uid("asha");
        Job j = repo.jobByNo("NFPL-ABSENT-001").orElseThrow();
        assertEquals("UNKNOWN_SUBJECT", eval.decide(999999L, "PREPARE", j.id()).code());
        assertEquals("UNKNOWN_SUBJECT", eval.decide(asha, "PREPARE", 999999L).code());
        assertEquals("UNKNOWN_ACTION", eval.decide(asha, "FLY_TO_MOON", j.id()).code());
        Decision broken = eval.decide(asha, "PREPARE", j, null);                           // a broken input: must not allow
        assertFalse(broken.allowed());
        assertEquals("UNDETERMINED", broken.code());
    }

    // ----------------------------------------------------------------- added question: two people act at once

    @Test
    void twoPeopleActingAtOnceOnlyOneWins() throws Exception {
        long ravi = uid("ravi"), sanjay = uid("sanjay");
        long job = preparedJob(3, 1, "IMPORT", "AIR");       // both Ravi and Sanjay may check this
        CountDownLatch go = new CountDownLatch(1);
        Callable<String> byRavi = () -> { go.await(); try { jobs.perform(ravi, "CHECK", job, null); return "OK"; } catch (ApiException e) { return e.code; } };
        Callable<String> bySanjay = () -> { go.await(); try { jobs.perform(sanjay, "CHECK", job, null); return "OK"; } catch (ApiException e) { return e.code; } };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<String> f1 = pool.submit(byRavi), f2 = pool.submit(bySanjay);
        go.countDown();
        List<String> results = List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(1, results.stream().filter("OK"::equals).count(), results.toString());
        assertEquals("CHECKED", repo.job(job).orElseThrow().stage());
    }

    // ----------------------------------------------------------------- added question: absent / disabled people

    @Test
    void statusChangesTakeEffectAtOnce() {
        long dev = uid("dev"), asha = uid("asha"), vikram = uid("vikram");
        long job = job("NFPL-ABSENT-001");

        grants.changeStatus(dev, asha, "ABSENT", "leave");
        assertEquals("USER_NOT_ACTIVE", eval.decide(asha, "PREPARE", job).code());
        assertEquals(0L, queues.queue(asha, "all", 50, null, null).count());
        grants.changeStatus(dev, asha, "ACTIVE", "back");
        assertTrue(eval.decide(asha, "PREPARE", job).allowed());

        assertEquals("SELF_CHANGE", fails(() -> grants.changeStatus(dev, dev, "ABSENT", "x")).code);

        Map<String, Object> disabled = grants.changeStatus(dev, vikram, "DISABLED", "left the company");
        assertTrue(((Number) disabled.get("jobsReleasedToPool")).intValue() >= 1);
        assertEquals(0, repo.jdbc.queryForObject("SELECT COUNT(*) FROM job WHERE owner_id = :u AND stage IN "
                + "('CONFIRMED','PREPARED','CHECKED','REOPENED')", p("u", vikram), Integer.class));
    }
}
