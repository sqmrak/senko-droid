#ifndef SENKO_HELPER_JOBS_H
#define SENKO_HELPER_JOBS_H

#include <pthread.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* work the control loop must not wait on, such as a handshake probe against a
   server that may not answer. each job runs on a thread of its own and hands
   back lines of text. the engine runs inside the app process, where forking
   a helper is not safe, so these are threads and not child processes.

   a job cannot be killed, so its function must bound its own wait */

#define HELPER_JOB_MAX 4
#define HELPER_JOB_OUTPUT_MAX 1024
#define HELPER_JOB_ARG_MAX 300

/* fills out with the lines to show and returns the exit code, 0 for success */
typedef int (*helper_job_fn)(const char *arg, char *out, size_t out_cap);

struct helper_jobs;

typedef struct {
    struct helper_jobs *owner;
    int           used;
    pthread_t     thread;
    int           wake[2];       /* the thread writes a byte when it is done */
    int           finished;      /* set by the thread, read after the wake byte */
    int           client_slot;   /* who asked, and which connection it was */
    uint64_t      client_generation;
    int64_t       started_ms;
    helper_job_fn fn;
    char          arg[HELPER_JOB_ARG_MAX];
    int           exit_code;
    char          output[HELPER_JOB_OUTPUT_MAX];
    size_t        output_len;
} helper_job_t;

typedef struct helper_jobs {
    pthread_mutex_t lock;
    helper_job_t jobs[HELPER_JOB_MAX];
} helper_jobs_t;

typedef struct {
    int      client_slot;
    uint64_t client_generation;
    int      exit_code;
    int64_t  elapsed_ms;
    const char *output;  /* valid until the next call */
    size_t   output_len;
} helper_job_done_t;

void helper_jobs_init(helper_jobs_t *jobs);

/* runs fn(arg) on a new thread. 0 on success, -1 with the reason */
int helper_jobs_start(helper_jobs_t *jobs, helper_job_fn fn, const char *arg,
                      int client_slot, uint64_t client_generation, int64_t now_ms,
                      char *reason, size_t reason_cap);

/* fills fds with the descriptors to poll for reading; returns how many */
size_t helper_jobs_fds(const helper_jobs_t *jobs, int *fds, size_t cap);

/* joins every finished job and hands it to done once */
void helper_jobs_service(helper_jobs_t *jobs, int64_t now_ms,
                         void (*done)(void *ctx, const helper_job_done_t *job),
                         void *ctx);

/* waits for every running job, then releases them. for shutdown */
void helper_jobs_stop(helper_jobs_t *jobs);

#ifdef __cplusplus
}
#endif

#endif
