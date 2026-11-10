#define _DEFAULT_SOURCE

#include "helper_jobs.h"

#include "core/errno_text.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static void set_reason(char *reason, size_t cap, const char *what, int value) {
    if (!reason || !cap) return;
    snprintf(reason, cap, "%s", what);
    if (value) senko_errno_append(reason, cap, value);
}

void helper_jobs_init(helper_jobs_t *jobs) {
    if (!jobs) return;
    memset(jobs, 0, sizeof *jobs);
    pthread_mutex_init(&jobs->lock, NULL);
    for (size_t i = 0; i < HELPER_JOB_MAX; ++i)
        jobs->jobs[i].wake[0] = jobs->jobs[i].wake[1] = -1;
}

static void *job_main(void *arg) {
    helper_job_t *job = arg;
    char out[HELPER_JOB_OUTPUT_MAX];
    out[0] = '\0';
    int code = job->fn(job->arg, out, sizeof out);
    pthread_mutex_lock(&job->owner->lock);
    snprintf(job->output, sizeof job->output, "%s", out);
    job->output_len = strlen(job->output);
    job->exit_code = code;
    job->finished = 1;
    pthread_mutex_unlock(&job->owner->lock);
    ssize_t wrote;
    do {
        wrote = write(job->wake[1], "d", 1);
    } while (wrote < 0 && errno == EINTR);
    return NULL;
}

int helper_jobs_start(helper_jobs_t *jobs, helper_job_fn fn, const char *arg,
                      int client_slot, uint64_t client_generation, int64_t now_ms,
                      char *reason, size_t reason_cap) {
    if (reason && reason_cap) reason[0] = '\0';
    if (!jobs || !fn) return -1;
    if (arg && strlen(arg) >= HELPER_JOB_ARG_MAX) {
        set_reason(reason, reason_cap, "the request is too long", 0);
        return -1;
    }
    helper_job_t *job = NULL;
    for (size_t i = 0; i < HELPER_JOB_MAX && !job; ++i)
        if (!jobs->jobs[i].used) job = &jobs->jobs[i];
    if (!job) {
        set_reason(reason, reason_cap, "too many requests are running", 0);
        return -1;
    }
    int pipefd[2];
    if (pipe(pipefd) != 0) {
        set_reason(reason, reason_cap, "cannot create a pipe for the request", errno);
        return -1;
    }
    for (int i = 0; i < 2; ++i)
        (void)fcntl(pipefd[i], F_SETFD, FD_CLOEXEC);
    (void)fcntl(pipefd[0], F_SETFL, fcntl(pipefd[0], F_GETFL, 0) | O_NONBLOCK);

    memset(job, 0, sizeof *job);
    job->used = 1;
    job->wake[0] = pipefd[0];
    job->wake[1] = pipefd[1];
    job->fn = fn;
    if (arg) snprintf(job->arg, sizeof job->arg, "%s", arg);
    job->client_slot = client_slot;
    job->client_generation = client_generation;
    job->started_ms = now_ms;

    job->owner = jobs;
    int rc = pthread_create(&job->thread, NULL, job_main, job);
    if (rc != 0) {
        close(pipefd[0]);
        close(pipefd[1]);
        memset(job, 0, sizeof *job);
        job->wake[0] = job->wake[1] = -1;
        set_reason(reason, reason_cap, "cannot start the request thread", rc);
        return -1;
    }
    return 0;
}

size_t helper_jobs_fds(const helper_jobs_t *jobs, int *fds, size_t cap) {
    size_t n = 0;
    for (size_t i = 0; jobs && i < HELPER_JOB_MAX && n < cap; ++i)
        if (jobs->jobs[i].used && jobs->jobs[i].wake[0] >= 0) fds[n++] = jobs->jobs[i].wake[0];
    return n;
}

static void release(helper_job_t *job) {
    if (job->wake[0] >= 0) close(job->wake[0]);
    if (job->wake[1] >= 0) close(job->wake[1]);
    memset(job, 0, sizeof *job);
    job->wake[0] = job->wake[1] = -1;
}

void helper_jobs_service(helper_jobs_t *jobs, int64_t now_ms,
                         void (*done)(void *ctx, const helper_job_done_t *job),
                         void *ctx) {
    if (!jobs) return;
    for (size_t i = 0; i < HELPER_JOB_MAX; ++i) {
        helper_job_t *job = &jobs->jobs[i];
        if (!job->used) continue;
        pthread_mutex_lock(&jobs->lock);
        int finished = job->finished;
        pthread_mutex_unlock(&jobs->lock);
        if (!finished) continue;
        pthread_join(job->thread, NULL);

        helper_job_done_t result;
        memset(&result, 0, sizeof result);
        result.client_slot = job->client_slot;
        result.client_generation = job->client_generation;
        result.elapsed_ms = now_ms - job->started_ms;
        result.exit_code = job->exit_code;
        result.output = job->output;
        result.output_len = job->output_len;
        if (done) done(ctx, &result);
        release(job);
    }
}

void helper_jobs_stop(helper_jobs_t *jobs) {
    if (!jobs) return;
    for (size_t i = 0; i < HELPER_JOB_MAX; ++i) {
        helper_job_t *job = &jobs->jobs[i];
        if (!job->used) continue;
        pthread_join(job->thread, NULL);
        release(job);
    }
    pthread_mutex_destroy(&jobs->lock);
}
