#include "helper_jobs.h"

#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static int failures;

static void ok(const char *name, int condition) {
    if (condition) return;
    ++failures;
    fprintf(stderr, "FAIL %s\n", name);
}

static int echo_job(const char *arg, char *out, size_t cap) {
    snprintf(out, cap, "echo %s", arg);
    return 0;
}

static int failing_job(const char *arg, char *out, size_t cap) {
    (void)arg;
    snprintf(out, cap, "error nope");
    return 3;
}

static pthread_mutex_t gate = PTHREAD_MUTEX_INITIALIZER;

static int gated_job(const char *arg, char *out, size_t cap) {
    (void)arg;
    pthread_mutex_lock(&gate);
    pthread_mutex_unlock(&gate);
    snprintf(out, cap, "gated");
    return 0;
}

typedef struct {
    int count;
    int slot[HELPER_JOB_MAX + 2];
    uint64_t generation[HELPER_JOB_MAX + 2];
    int code[HELPER_JOB_MAX + 2];
    char text[HELPER_JOB_MAX + 2][64];
} collected_t;

static void collect(void *ctx, const helper_job_done_t *job) {
    collected_t *got = ctx;
    if (got->count >= HELPER_JOB_MAX + 2) return;
    int i = got->count++;
    got->slot[i] = job->client_slot;
    got->generation[i] = job->client_generation;
    got->code[i] = job->exit_code;
    snprintf(got->text[i], sizeof got->text[i], "%.*s", (int)job->output_len, job->output);
}

/* waits until the pool reports n finished jobs, polling the descriptors the
   way the control loop does */
static void wait_for(helper_jobs_t *jobs, collected_t *got, int n) {
    for (int round = 0; round < 200 && got->count < n; ++round) {
        int fds[HELPER_JOB_MAX];
        size_t count = helper_jobs_fds(jobs, fds, HELPER_JOB_MAX);
        struct pollfd pfd[HELPER_JOB_MAX];
        for (size_t i = 0; i < count; ++i) {
            pfd[i].fd = fds[i];
            pfd[i].events = POLLIN;
            pfd[i].revents = 0;
        }
        (void)poll(pfd, (nfds_t)count, 50);
        helper_jobs_service(jobs, 1000, collect, got);
    }
}

int main(void) {
    char reason[128];
    helper_jobs_t jobs;
    helper_jobs_init(&jobs);

    collected_t got;
    memset(&got, 0, sizeof got);
    ok("start echo", helper_jobs_start(&jobs, echo_job, "hello", 2, 77, 0,
                                       reason, sizeof reason) == 0);
    ok("start failing", helper_jobs_start(&jobs, failing_job, "", 5, 9, 0,
                                          reason, sizeof reason) == 0);
    wait_for(&jobs, &got, 2);
    ok("both finished", got.count == 2);
    int echo_at = got.slot[0] == 2 ? 0 : 1;
    int fail_at = 1 - echo_at;
    ok("echo output", strcmp(got.text[echo_at], "echo hello") == 0 && got.code[echo_at] == 0);
    ok("echo belongs to its client", got.slot[echo_at] == 2 && got.generation[echo_at] == 77);
    ok("failure output", strcmp(got.text[fail_at], "error nope") == 0 && got.code[fail_at] == 3);
    ok("failure belongs to its client", got.slot[fail_at] == 5 && got.generation[fail_at] == 9);

    int fds[HELPER_JOB_MAX];
    ok("nothing left to poll", helper_jobs_fds(&jobs, fds, HELPER_JOB_MAX) == 0);

    /* every slot taken, then one more */
    pthread_mutex_lock(&gate);
    int started = 0;
    for (int i = 0; i < HELPER_JOB_MAX; ++i)
        started += helper_jobs_start(&jobs, gated_job, "", i, 1, 0, reason, sizeof reason) == 0;
    ok("all slots start", started == HELPER_JOB_MAX);
    ok("pool full is refused", helper_jobs_start(&jobs, echo_job, "x", 0, 1, 0, reason,
                                                 sizeof reason) == -1 &&
                               strstr(reason, "too many") != NULL);
    ok("full pool polls every job", helper_jobs_fds(&jobs, fds, HELPER_JOB_MAX) ==
                                    (size_t)HELPER_JOB_MAX);
    pthread_mutex_unlock(&gate);
    memset(&got, 0, sizeof got);
    wait_for(&jobs, &got, HELPER_JOB_MAX);
    ok("gated jobs finish", got.count == HELPER_JOB_MAX);
    ok("a slot is free again", helper_jobs_start(&jobs, echo_job, "again", 1, 2, 0, reason,
                                                 sizeof reason) == 0);

    char long_arg[HELPER_JOB_ARG_MAX + 8];
    memset(long_arg, 'a', sizeof long_arg);
    long_arg[sizeof long_arg - 1] = '\0';
    ok("long argument is refused", helper_jobs_start(&jobs, echo_job, long_arg, 0, 1, 0,
                                                     reason, sizeof reason) == -1);
    ok("missing function is refused", helper_jobs_start(&jobs, NULL, "", 0, 1, 0, reason,
                                                        sizeof reason) == -1);

    helper_jobs_stop(&jobs);
    ok("stop leaves nothing", helper_jobs_fds(&jobs, fds, HELPER_JOB_MAX) == 0);

    if (failures) {
        fprintf(stderr, "%d check(s) failed\n", failures);
        return 1;
    }
    puts("ok");
    return 0;
}
