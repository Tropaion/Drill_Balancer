#include <stdio.h>
#include <string.h>
#include "unity.h"
#include "rec_fsm.h"

#define T0 1000u

static rec_fsm_t f;
static rec_env_t env;

void setUp(void)
{
    rec_fsm_init(&f, T0);
    env = (rec_env_t){.battery_valid = true, .battery_pct = 80};
}

void tearDown(void) {}

static rec_cmd_t take_cmd(void)
{
    return (rec_cmd_t){.op = REC_CMD_TAKE_START, .profile = REC_PROFILE_RAW448,
                       .mic_mask = REC_MIC_ALL, .gain_db = 6, .max_s = 300, .take_id = 42};
}

static rec_out_t cmd_at(const rec_cmd_t *c, uint32_t now)
{
    const rec_input_t in = {.kind = REC_IN_CMD, .cmd = c};
    return rec_fsm_step(&f, &in, &env, now);
}

static rec_out_t op_at(rec_cmd_op_t op, uint32_t now)
{
    rec_cmd_t c = op == REC_CMD_TAKE_START ? take_cmd() : (rec_cmd_t){.op = op};
    if (op == REC_CMD_MARK) {
        c.mark_code = 7;
        strcpy(c.text, "hole 1");
    }
    return cmd_at(&c, now);
}

static rec_out_t ev_at(rec_input_kind_t k, uint32_t now)
{
    const rec_input_t in = {.kind = k};
    return rec_fsm_step(&f, &in, &env, now);
}

// Drives the FSM from IDLE into s, entering it at T0.
static void go(rec_state_t s)
{
    rec_fsm_init(&f, T0);
    if (s == REC_STATE_IDLE) {
        return;
    }
    TEST_ASSERT_EQUAL(REC_OK, op_at(REC_CMD_OPEN, T0).err);
    if (s == REC_STATE_AP_STARTING) {
        return;
    }
    ev_at(REC_IN_AP_UP, T0);
    if (s == REC_STATE_WAIT_CLIENT) {
        return;
    }
    ev_at(REC_IN_HELLO_OK, T0);
    if (s == REC_STATE_READY) {
        return;
    }
    if (s == REC_STATE_CLOSING) {
        op_at(REC_CMD_CLOSE, T0);
        return;
    }
    TEST_ASSERT_EQUAL(REC_OK, op_at(REC_CMD_TAKE_START, T0).err);
    if (s == REC_STATE_RECORDING) {
        return;
    }
    op_at(REC_CMD_STOP, T0);
}

// ---- every state x every input --------------------------------------------------

// Inputs of the table: the five commands, then the events REC_IN_AP_UP ..
// REC_IN_TICK.
enum { I_OPEN, I_CLOSE, I_TAKE, I_STOP, I_MARK, I_EVENTS };
#define N_INPUTS (I_EVENTS + REC_IN_COUNT - REC_IN_AP_UP)
#define EV(k) (I_EVENTS + (k) - REC_IN_AP_UP)

#define NTF   REC_ACT_NOTIFY
#define SAP   REC_ACT_START_AP
#define XAP   REC_ACT_STOP_AP
#define AON   REC_ACT_AUDIO_ON
#define AOFF  REC_ACT_AUDIO_OFF
#define SPR   REC_ACT_SET_PROFILE
#define SES   REC_ACT_EMIT_SESSION
#define EVT   REC_ACT_EMIT_EVENT
#define END   REC_ACT_EMIT_END
#define RRST  REC_ACT_RING_RESET
#define FLU   REC_ACT_RING_FLUSH

typedef struct {
    rec_state_t from;
    int input;
    rec_state_t to;
    uint32_t actions;
    rec_err_t err;
    uint16_t event;
} row_t;

#define S(x) REC_STATE_##x
static const row_t TABLE[] = {
    {S(IDLE), I_OPEN, S(AP_STARTING), SAP | NTF, REC_OK, 0},
    {S(IDLE), I_CLOSE, S(IDLE), 0, REC_OK, 0},
    {S(IDLE), I_TAKE, S(IDLE), 0, REC_ERR_STATE, 0},
    {S(IDLE), I_STOP, S(IDLE), 0, REC_ERR_STATE, 0},
    {S(IDLE), I_MARK, S(IDLE), 0, REC_ERR_STATE, 0},
    {S(IDLE), EV(REC_IN_AP_UP), S(IDLE), 0, REC_OK, 0},
    {S(IDLE), EV(REC_IN_AP_FAIL), S(IDLE), 0, REC_OK, 0},
    {S(IDLE), EV(REC_IN_HELLO_OK), S(IDLE), 0, REC_OK, 0},
    {S(IDLE), EV(REC_IN_CLIENT_LOST), S(IDLE), 0, REC_OK, 0},
    {S(IDLE), EV(REC_IN_BLE_LOST), S(IDLE), 0, REC_OK, 0},
    {S(IDLE), EV(REC_IN_BLE_BACK), S(IDLE), 0, REC_OK, 0},
    {S(IDLE), EV(REC_IN_IMU_LOST), S(IDLE), 0, REC_OK, 0},
    {S(IDLE), EV(REC_IN_IMU_BACK), S(IDLE), 0, REC_OK, 0},
    {S(IDLE), EV(REC_IN_DRAINED), S(IDLE), 0, REC_OK, 0},
    {S(IDLE), EV(REC_IN_AP_DOWN), S(IDLE), 0, REC_OK, 0},
    {S(IDLE), EV(REC_IN_SHUTDOWN), S(IDLE), 0, REC_OK, 0},
    {S(IDLE), EV(REC_IN_TICK), S(IDLE), 0, REC_OK, 0},

    {S(AP_STARTING), I_OPEN, S(AP_STARTING), 0, REC_OK, 0},
    {S(AP_STARTING), I_CLOSE, S(CLOSING), XAP | NTF, REC_OK, 0},
    {S(AP_STARTING), I_TAKE, S(AP_STARTING), 0, REC_ERR_STATE, 0},
    {S(AP_STARTING), I_STOP, S(AP_STARTING), 0, REC_ERR_STATE, 0},
    {S(AP_STARTING), I_MARK, S(AP_STARTING), 0, REC_ERR_STATE, 0},
    {S(AP_STARTING), EV(REC_IN_AP_UP), S(WAIT_CLIENT), NTF, REC_OK, 0},
    {S(AP_STARTING), EV(REC_IN_AP_FAIL), S(CLOSING), XAP | NTF, REC_OK, 0},
    {S(AP_STARTING), EV(REC_IN_HELLO_OK), S(AP_STARTING), 0, REC_OK, 0},
    {S(AP_STARTING), EV(REC_IN_CLIENT_LOST), S(AP_STARTING), 0, REC_OK, 0},
    {S(AP_STARTING), EV(REC_IN_BLE_LOST), S(AP_STARTING), 0, REC_OK, 0},
    {S(AP_STARTING), EV(REC_IN_BLE_BACK), S(AP_STARTING), 0, REC_OK, 0},
    {S(AP_STARTING), EV(REC_IN_IMU_LOST), S(AP_STARTING), NTF, REC_OK, 0},
    {S(AP_STARTING), EV(REC_IN_IMU_BACK), S(AP_STARTING), 0, REC_OK, 0},
    {S(AP_STARTING), EV(REC_IN_DRAINED), S(AP_STARTING), 0, REC_OK, 0},
    {S(AP_STARTING), EV(REC_IN_AP_DOWN), S(AP_STARTING), 0, REC_OK, 0},
    {S(AP_STARTING), EV(REC_IN_SHUTDOWN), S(CLOSING), XAP | NTF, REC_OK, 0},
    {S(AP_STARTING), EV(REC_IN_TICK), S(AP_STARTING), 0, REC_OK, 0},

    {S(WAIT_CLIENT), I_OPEN, S(WAIT_CLIENT), 0, REC_OK, 0},
    {S(WAIT_CLIENT), I_CLOSE, S(CLOSING), XAP | NTF, REC_OK, 0},
    {S(WAIT_CLIENT), I_TAKE, S(WAIT_CLIENT), 0, REC_ERR_STATE, 0},
    {S(WAIT_CLIENT), I_STOP, S(WAIT_CLIENT), 0, REC_ERR_STATE, 0},
    {S(WAIT_CLIENT), I_MARK, S(WAIT_CLIENT), 0, REC_ERR_STATE, 0},
    {S(WAIT_CLIENT), EV(REC_IN_AP_UP), S(WAIT_CLIENT), 0, REC_OK, 0},
    {S(WAIT_CLIENT), EV(REC_IN_AP_FAIL), S(WAIT_CLIENT), 0, REC_OK, 0},
    {S(WAIT_CLIENT), EV(REC_IN_HELLO_OK), S(READY), NTF, REC_OK, 0},
    {S(WAIT_CLIENT), EV(REC_IN_CLIENT_LOST), S(WAIT_CLIENT), 0, REC_OK, 0},
    {S(WAIT_CLIENT), EV(REC_IN_BLE_LOST), S(WAIT_CLIENT), 0, REC_OK, 0},
    {S(WAIT_CLIENT), EV(REC_IN_BLE_BACK), S(WAIT_CLIENT), 0, REC_OK, 0},
    {S(WAIT_CLIENT), EV(REC_IN_IMU_LOST), S(WAIT_CLIENT), NTF, REC_OK, 0},
    {S(WAIT_CLIENT), EV(REC_IN_IMU_BACK), S(WAIT_CLIENT), 0, REC_OK, 0},
    {S(WAIT_CLIENT), EV(REC_IN_DRAINED), S(WAIT_CLIENT), 0, REC_OK, 0},
    {S(WAIT_CLIENT), EV(REC_IN_AP_DOWN), S(WAIT_CLIENT), 0, REC_OK, 0},
    {S(WAIT_CLIENT), EV(REC_IN_SHUTDOWN), S(CLOSING), XAP | NTF, REC_OK, 0},
    {S(WAIT_CLIENT), EV(REC_IN_TICK), S(WAIT_CLIENT), 0, REC_OK, 0},

    {S(READY), I_OPEN, S(READY), 0, REC_OK, 0},
    {S(READY), I_CLOSE, S(CLOSING), XAP | NTF, REC_OK, 0},
    {S(READY), I_TAKE, S(RECORDING), SES | SPR | AON | NTF, REC_OK, 0},
    {S(READY), I_STOP, S(READY), 0, REC_OK, 0},
    {S(READY), I_MARK, S(READY), 0, REC_ERR_STATE, 0},
    {S(READY), EV(REC_IN_AP_UP), S(READY), 0, REC_OK, 0},
    {S(READY), EV(REC_IN_AP_FAIL), S(READY), 0, REC_OK, 0},
    {S(READY), EV(REC_IN_HELLO_OK), S(READY), 0, REC_OK, 0},
    {S(READY), EV(REC_IN_CLIENT_LOST), S(WAIT_CLIENT), RRST | NTF, REC_OK, 0},
    {S(READY), EV(REC_IN_BLE_LOST), S(READY), 0, REC_OK, 0},
    {S(READY), EV(REC_IN_BLE_BACK), S(READY), 0, REC_OK, 0},
    {S(READY), EV(REC_IN_IMU_LOST), S(READY), NTF, REC_OK, 0},
    {S(READY), EV(REC_IN_IMU_BACK), S(READY), 0, REC_OK, 0},
    {S(READY), EV(REC_IN_DRAINED), S(READY), 0, REC_OK, 0},
    {S(READY), EV(REC_IN_AP_DOWN), S(READY), 0, REC_OK, 0},
    {S(READY), EV(REC_IN_SHUTDOWN), S(CLOSING), XAP | NTF, REC_OK, 0},
    {S(READY), EV(REC_IN_TICK), S(READY), 0, REC_OK, 0},

    {S(RECORDING), I_OPEN, S(RECORDING), 0, REC_OK, 0},
    {S(RECORDING), I_CLOSE, S(DRAINING), AOFF | SPR | NTF, REC_OK, 0},
    {S(RECORDING), I_TAKE, S(RECORDING), 0, REC_ERR_STATE, 0},
    {S(RECORDING), I_STOP, S(DRAINING), AOFF | SPR | NTF, REC_OK, 0},
    {S(RECORDING), I_MARK, S(RECORDING), EVT, REC_OK, REC_EV_MARK},
    {S(RECORDING), EV(REC_IN_AP_UP), S(RECORDING), 0, REC_OK, 0},
    {S(RECORDING), EV(REC_IN_AP_FAIL), S(RECORDING), 0, REC_OK, 0},
    {S(RECORDING), EV(REC_IN_HELLO_OK), S(RECORDING), 0, REC_OK, 0},
    {S(RECORDING), EV(REC_IN_CLIENT_LOST), S(WAIT_CLIENT), RRST | AOFF | SPR | NTF, REC_OK, 0},
    {S(RECORDING), EV(REC_IN_BLE_LOST), S(RECORDING), EVT, REC_OK, REC_EV_BLE_LOST},
    {S(RECORDING), EV(REC_IN_BLE_BACK), S(RECORDING), 0, REC_OK, 0},
    {S(RECORDING), EV(REC_IN_IMU_LOST), S(RECORDING), EVT | NTF, REC_OK, REC_EV_IMU_LOST},
    {S(RECORDING), EV(REC_IN_IMU_BACK), S(RECORDING), 0, REC_OK, 0},
    {S(RECORDING), EV(REC_IN_DRAINED), S(RECORDING), 0, REC_OK, 0},
    {S(RECORDING), EV(REC_IN_AP_DOWN), S(RECORDING), 0, REC_OK, 0},
    {S(RECORDING), EV(REC_IN_SHUTDOWN), S(CLOSING), AOFF | SPR | FLU | END | XAP | NTF, REC_OK, 0},
    {S(RECORDING), EV(REC_IN_TICK), S(RECORDING), 0, REC_OK, 0},

    {S(DRAINING), I_OPEN, S(DRAINING), 0, REC_OK, 0},
    {S(DRAINING), I_CLOSE, S(DRAINING), 0, REC_OK, 0},
    {S(DRAINING), I_TAKE, S(DRAINING), 0, REC_ERR_STATE, 0},
    {S(DRAINING), I_STOP, S(DRAINING), 0, REC_OK, 0},
    {S(DRAINING), I_MARK, S(DRAINING), 0, REC_ERR_STATE, 0},
    {S(DRAINING), EV(REC_IN_AP_UP), S(DRAINING), 0, REC_OK, 0},
    {S(DRAINING), EV(REC_IN_AP_FAIL), S(DRAINING), 0, REC_OK, 0},
    {S(DRAINING), EV(REC_IN_HELLO_OK), S(DRAINING), 0, REC_OK, 0},
    {S(DRAINING), EV(REC_IN_CLIENT_LOST), S(WAIT_CLIENT), RRST | NTF, REC_OK, 0},
    {S(DRAINING), EV(REC_IN_BLE_LOST), S(DRAINING), 0, REC_OK, 0},
    {S(DRAINING), EV(REC_IN_BLE_BACK), S(DRAINING), 0, REC_OK, 0},
    {S(DRAINING), EV(REC_IN_IMU_LOST), S(DRAINING), NTF, REC_OK, 0},
    {S(DRAINING), EV(REC_IN_IMU_BACK), S(DRAINING), 0, REC_OK, 0},
    {S(DRAINING), EV(REC_IN_DRAINED), S(DRAINING), END, REC_OK, 0},
    {S(DRAINING), EV(REC_IN_AP_DOWN), S(DRAINING), 0, REC_OK, 0},
    {S(DRAINING), EV(REC_IN_SHUTDOWN), S(CLOSING), FLU | END | XAP | NTF, REC_OK, 0},
    {S(DRAINING), EV(REC_IN_TICK), S(DRAINING), 0, REC_OK, 0},

    {S(CLOSING), I_OPEN, S(CLOSING), 0, REC_ERR_STATE, 0},
    {S(CLOSING), I_CLOSE, S(CLOSING), 0, REC_OK, 0},
    {S(CLOSING), I_TAKE, S(CLOSING), 0, REC_ERR_STATE, 0},
    {S(CLOSING), I_STOP, S(CLOSING), 0, REC_ERR_STATE, 0},
    {S(CLOSING), I_MARK, S(CLOSING), 0, REC_ERR_STATE, 0},
    {S(CLOSING), EV(REC_IN_AP_UP), S(CLOSING), 0, REC_OK, 0},
    {S(CLOSING), EV(REC_IN_AP_FAIL), S(CLOSING), 0, REC_OK, 0},
    {S(CLOSING), EV(REC_IN_HELLO_OK), S(CLOSING), 0, REC_OK, 0},
    {S(CLOSING), EV(REC_IN_CLIENT_LOST), S(CLOSING), 0, REC_OK, 0},
    {S(CLOSING), EV(REC_IN_BLE_LOST), S(CLOSING), 0, REC_OK, 0},
    {S(CLOSING), EV(REC_IN_BLE_BACK), S(CLOSING), 0, REC_OK, 0},
    {S(CLOSING), EV(REC_IN_IMU_LOST), S(CLOSING), 0, REC_OK, 0},
    {S(CLOSING), EV(REC_IN_IMU_BACK), S(CLOSING), 0, REC_OK, 0},
    {S(CLOSING), EV(REC_IN_DRAINED), S(CLOSING), 0, REC_OK, 0},
    {S(CLOSING), EV(REC_IN_AP_DOWN), S(IDLE), NTF, REC_OK, 0},
    {S(CLOSING), EV(REC_IN_SHUTDOWN), S(CLOSING), 0, REC_OK, 0},
    {S(CLOSING), EV(REC_IN_TICK), S(CLOSING), 0, REC_OK, 0},
};

static rec_out_t apply(int input, uint32_t now)
{
    switch (input) {
    case I_OPEN:  return op_at(REC_CMD_OPEN, now);
    case I_CLOSE: return op_at(REC_CMD_CLOSE, now);
    case I_TAKE:  return op_at(REC_CMD_TAKE_START, now);
    case I_STOP:  return op_at(REC_CMD_STOP, now);
    case I_MARK:  return op_at(REC_CMD_MARK, now);
    default:      return ev_at((rec_input_kind_t)(input - I_EVENTS + REC_IN_AP_UP), now);
    }
}

static void test_every_state_and_input(void)
{
    // The table has each (state, input) exactly once.
    TEST_ASSERT_EQUAL_UINT(REC_STATE_COUNT * N_INPUTS, sizeof(TABLE) / sizeof(TABLE[0]));
    static bool seen[REC_STATE_COUNT][N_INPUTS];
    memset(seen, 0, sizeof(seen));
    char msg[64];
    for (size_t i = 0; i < sizeof(TABLE) / sizeof(TABLE[0]); i++) {
        const row_t *t = &TABLE[i];
        TEST_ASSERT_FALSE(seen[t->from][t->input]);
        seen[t->from][t->input] = true;

        go(t->from);
        TEST_ASSERT_EQUAL(t->from, f.state);
        rec_fsm_t before;
        memcpy(&before, &f, sizeof(f));
        rec_out_t o = apply(t->input, T0 + 1);
        snprintf(msg, sizeof(msg), "row %u (state %d, input %d)", (unsigned)i, (int)t->from,
                 t->input);
        TEST_ASSERT_EQUAL_MESSAGE(t->to, f.state, msg);
        TEST_ASSERT_EQUAL_HEX32_MESSAGE(t->actions, o.actions, msg);
        TEST_ASSERT_EQUAL_MESSAGE(t->err, o.err, msg);
        if (t->actions & REC_ACT_EMIT_EVENT) {
            TEST_ASSERT_EQUAL_UINT16_MESSAGE(t->event, o.event_code, msg);
        }
        if (o.err != REC_OK) {
            // A refused command changes nothing.
            TEST_ASSERT_EQUAL_MEMORY_MESSAGE(&before, &f, sizeof(f), msg);
        }
    }
}

// ---- commands -----------------------------------------------------------------

static void test_open_refused_while_ota_or_calibration(void)
{
    env.ota_active = true;
    TEST_ASSERT_EQUAL(REC_ERR_STATE, op_at(REC_CMD_OPEN, T0).err);
    env.ota_active = false;
    env.calibrating = true;
    TEST_ASSERT_EQUAL(REC_ERR_STATE, op_at(REC_CMD_OPEN, T0).err);
    TEST_ASSERT_EQUAL(REC_STATE_IDLE, f.state);
    TEST_ASSERT_FALSE(rec_fsm_session_open(&f));
    env.calibrating = false;
    TEST_ASSERT_EQUAL(REC_OK, op_at(REC_CMD_OPEN, T0).err);
    TEST_ASSERT_TRUE(rec_fsm_session_open(&f));
}

static void test_take_start_checks_its_arguments(void)
{
    go(REC_STATE_READY);
    rec_cmd_t c = take_cmd();
    c.profile = REC_PROFILE_COUNT;
    TEST_ASSERT_EQUAL(REC_ERR_BAD_ARG, cmd_at(&c, T0).err);
    c = take_cmd();
    c.mic_mask = 0x04;
    TEST_ASSERT_EQUAL(REC_ERR_BAD_ARG, cmd_at(&c, T0).err);
    c = take_cmd();
    c.gain_db = REC_GAIN_MAX_DB + 1;
    TEST_ASSERT_EQUAL(REC_ERR_BAD_ARG, cmd_at(&c, T0).err);
    c = take_cmd();
    c.max_s = REC_TAKE_MAX_S + 1;
    TEST_ASSERT_EQUAL(REC_ERR_BAD_ARG, cmd_at(&c, T0).err);
    TEST_ASSERT_EQUAL(REC_STATE_READY, f.state);

    // Limits themselves are fine; every profile is allowed.
    c = take_cmd();
    c.profile = REC_PROFILE_RAW897;
    c.gain_db = REC_GAIN_MAX_DB;
    c.max_s = REC_TAKE_MAX_S;
    rec_out_t o = cmd_at(&c, T0);
    TEST_ASSERT_EQUAL(REC_OK, o.err);
    TEST_ASSERT_EQUAL_UINT16(REC_TAKE_MAX_S, f.max_s);
    TEST_ASSERT_EQUAL_UINT8(REC_PROFILE_RAW897, rec_fsm_profile(&f));
    TEST_ASSERT_EQUAL_UINT32(42, f.take_id);
    TEST_ASSERT_EQUAL_UINT8(REC_MIC_ALL, f.mic_mask);
    TEST_ASSERT_EQUAL_UINT8(REC_GAIN_MAX_DB, f.gain_db);
}

static void test_take_defaults_and_no_audio(void)
{
    go(REC_STATE_READY);
    rec_cmd_t c = take_cmd();
    c.max_s = 0;
    c.mic_mask = 0;
    c.profile = REC_PROFILE_LEVEL;
    rec_out_t o = cmd_at(&c, T0);
    TEST_ASSERT_EQUAL(REC_OK, o.err);
    TEST_ASSERT_EQUAL_UINT16(REC_TAKE_DEFAULT_S, f.max_s);
    // Without microphones nothing is switched on or off.
    TEST_ASSERT_EQUAL_HEX32(SES | SPR | NTF, o.actions);
    TEST_ASSERT_EQUAL_HEX32(SPR | NTF, op_at(REC_CMD_STOP, T0 + 5).actions);
    TEST_ASSERT_EQUAL_UINT8(REC_PROFILE_LEVEL, rec_fsm_profile(&f));
}

static void test_take_start_battery(void)
{
    go(REC_STATE_READY);
    env.battery_pct = REC_BATT_START_PCT - 1;
    TEST_ASSERT_EQUAL(REC_ERR_BATTERY, op_at(REC_CMD_TAKE_START, T0).err);
    // On USB the cell is not drained.
    env.vbus = true;
    TEST_ASSERT_EQUAL(REC_OK, op_at(REC_CMD_TAKE_START, T0).err);
    go(REC_STATE_READY);
    env.vbus = false;
    env.battery_valid = false;          // no reading: no reason to refuse
    TEST_ASSERT_EQUAL(REC_OK, op_at(REC_CMD_TAKE_START, T0).err);
    go(REC_STATE_READY);
    env.battery_valid = true;
    env.battery_pct = REC_BATT_START_PCT;
    TEST_ASSERT_EQUAL(REC_OK, op_at(REC_CMD_TAKE_START, T0).err);
}

static void test_take_start_refused_during_ota_or_calibration(void)
{
    go(REC_STATE_READY);
    env.ota_active = true;
    TEST_ASSERT_EQUAL(REC_ERR_STATE, op_at(REC_CMD_TAKE_START, T0).err);
    env.ota_active = false;
    env.calibrating = true;
    TEST_ASSERT_EQUAL(REC_ERR_STATE, op_at(REC_CMD_TAKE_START, T0).err);
    TEST_ASSERT_EQUAL(REC_STATE_READY, f.state);
}

static void test_mark_carries_its_code(void)
{
    go(REC_STATE_RECORDING);
    rec_cmd_t c = {.op = REC_CMD_MARK, .mark_code = 0xBEEF};
    rec_out_t o = cmd_at(&c, T0 + 10);
    TEST_ASSERT_EQUAL_HEX32(EVT, o.actions);
    TEST_ASSERT_EQUAL_UINT16(REC_EV_MARK, o.event_code);
    TEST_ASSERT_EQUAL_UINT16(0xBEEF, o.event_arg);
}

static void test_bad_commands(void)
{
    const rec_input_t in = {.kind = REC_IN_CMD, .cmd = NULL};
    TEST_ASSERT_EQUAL(REC_ERR_BAD_ARG, rec_fsm_step(&f, &in, &env, T0).err);
    rec_cmd_t c = {.op = (rec_cmd_op_t)99};
    TEST_ASSERT_EQUAL(REC_ERR_BAD_ARG, cmd_at(&c, T0).err);
    const rec_input_t odd = {.kind = REC_IN_COUNT};
    TEST_ASSERT_EQUAL_HEX32(0, rec_fsm_step(&f, &odd, &env, T0).actions);
    TEST_ASSERT_EQUAL(REC_STATE_IDLE, f.state);
}

// ---- whole flows ----------------------------------------------------------------

static void test_session_with_two_takes(void)
{
    TEST_ASSERT_EQUAL_HEX32(SAP | NTF, op_at(REC_CMD_OPEN, T0).actions);
    TEST_ASSERT_EQUAL_HEX32(NTF, ev_at(REC_IN_AP_UP, T0 + 500).actions);
    TEST_ASSERT_EQUAL_HEX32(NTF, ev_at(REC_IN_HELLO_OK, T0 + 9000).actions);
    TEST_ASSERT_EQUAL(REC_STATE_READY, f.state);
    TEST_ASSERT_EQUAL_UINT16(0, rec_fsm_elapsed_s(&f, T0 + 9000));

    uint32_t t = T0 + 10000;
    TEST_ASSERT_EQUAL(REC_OK, op_at(REC_CMD_TAKE_START, t).err);
    TEST_ASSERT_EQUAL_UINT8(REC_REASON_NONE, f.reason);
    TEST_ASSERT_EQUAL_UINT16(12, rec_fsm_elapsed_s(&f, t + 12999));
    TEST_ASSERT_EQUAL_HEX32(EVT, op_at(REC_CMD_MARK, t + 5000).actions);
    TEST_ASSERT_EQUAL_HEX32(AOFF | SPR | NTF, op_at(REC_CMD_STOP, t + 20000).actions);
    TEST_ASSERT_EQUAL_UINT8(REC_REASON_USER, f.reason);
    TEST_ASSERT_EQUAL_UINT8(REC_PROFILE_LEVEL, rec_fsm_profile(&f));
    // Frozen after the stop.
    TEST_ASSERT_EQUAL_UINT16(20, rec_fsm_elapsed_s(&f, t + 90000));
    // First the data drains, then END goes in and drains.
    TEST_ASSERT_EQUAL_HEX32(END, ev_at(REC_IN_DRAINED, t + 20100).actions);
    TEST_ASSERT_EQUAL(REC_STATE_DRAINING, f.state);
    TEST_ASSERT_EQUAL_HEX32(NTF, ev_at(REC_IN_DRAINED, t + 20200).actions);
    TEST_ASSERT_EQUAL(REC_STATE_READY, f.state);

    // A second take in the same session; CLOSE ends it and the session.
    t += 60000;
    rec_cmd_t c = take_cmd();
    c.take_id = 43;
    TEST_ASSERT_EQUAL(REC_OK, cmd_at(&c, t).err);
    TEST_ASSERT_EQUAL_UINT16(0, rec_fsm_elapsed_s(&f, t));
    TEST_ASSERT_EQUAL_HEX32(AOFF | SPR | NTF, op_at(REC_CMD_CLOSE, t + 3000).actions);
    TEST_ASSERT_TRUE(f.close_pending);
    TEST_ASSERT_EQUAL_HEX32(END, ev_at(REC_IN_DRAINED, t + 3100).actions);
    TEST_ASSERT_EQUAL_HEX32(XAP | NTF, ev_at(REC_IN_DRAINED, t + 3200).actions);
    TEST_ASSERT_EQUAL(REC_STATE_CLOSING, f.state);
    TEST_ASSERT_EQUAL_HEX32(NTF, ev_at(REC_IN_AP_DOWN, t + 3500).actions);
    TEST_ASSERT_EQUAL(REC_STATE_IDLE, f.state);
    TEST_ASSERT_EQUAL_UINT8(REC_REASON_USER, f.reason);
    TEST_ASSERT_EQUAL_UINT32(43, f.take_id);

    // A new session forgets the old take and reason.
    TEST_ASSERT_EQUAL(REC_OK, op_at(REC_CMD_OPEN, t + 9000).err);
    TEST_ASSERT_EQUAL_UINT8(REC_REASON_NONE, f.reason);
    TEST_ASSERT_FALSE(f.take_valid);
    TEST_ASSERT_EQUAL_UINT16(0, rec_fsm_elapsed_s(&f, t + 9000));
}

// An empty ring sends no DRAINED: the take is done at once.
static void test_stop_with_an_empty_ring(void)
{
    env.ring_empty = true;
    go(REC_STATE_RECORDING);
    TEST_ASSERT_EQUAL_HEX32(AOFF | SPR | END | NTF, op_at(REC_CMD_STOP, T0 + 3000).actions);
    TEST_ASSERT_EQUAL(REC_STATE_READY, f.state);
    TEST_ASSERT_TRUE(f.end_emitted);
    TEST_ASSERT_EQUAL_UINT8(REC_REASON_USER, f.reason);
    TEST_ASSERT_EQUAL_UINT16(3, rec_fsm_elapsed_s(&f, T0 + 9000));
    // The next take can start right away; END's own DRAINED changes nothing.
    TEST_ASSERT_EQUAL_HEX32(0, ev_at(REC_IN_DRAINED, T0 + 3001).actions);
    TEST_ASSERT_EQUAL(REC_OK, op_at(REC_CMD_TAKE_START, T0 + 3002).err);

    // A close goes on to CLOSING, the limits and the BLE grace alike.
    go(REC_STATE_RECORDING);
    TEST_ASSERT_EQUAL_HEX32(AOFF | SPR | END | XAP | NTF, op_at(REC_CMD_CLOSE, T0 + 1).actions);
    TEST_ASSERT_EQUAL(REC_STATE_CLOSING, f.state);
    go(REC_STATE_RECORDING);
    TEST_ASSERT_EQUAL_HEX32(AOFF | SPR | END | NTF, ev_at(REC_IN_TICK, T0 + 300000).actions);
    TEST_ASSERT_EQUAL(REC_STATE_READY, f.state);
    TEST_ASSERT_EQUAL_UINT8(REC_REASON_MAX_S, f.reason);
    go(REC_STATE_RECORDING);
    ev_at(REC_IN_BLE_LOST, T0 + 1);
    TEST_ASSERT_EQUAL_HEX32(AOFF | SPR | END | XAP | NTF,
                            ev_at(REC_IN_TICK, T0 + 1 + REC_BLE_GRACE_MS).actions);
    TEST_ASSERT_EQUAL(REC_STATE_CLOSING, f.state);
}

// A STOP that arrives after the take ended on its own is no error; before
// any take it is.
static void test_stop_racing_the_take_end(void)
{
    go(REC_STATE_RECORDING);
    ev_at(REC_IN_CLIENT_LOST, T0 + 10);
    TEST_ASSERT_EQUAL(REC_STATE_WAIT_CLIENT, f.state);
    rec_out_t o = op_at(REC_CMD_STOP, T0 + 11);
    TEST_ASSERT_EQUAL(REC_OK, o.err);
    TEST_ASSERT_EQUAL_HEX32(0, o.actions);
    TEST_ASSERT_EQUAL_UINT8(REC_REASON_CLIENT_LOST, f.reason);

    go(REC_STATE_RECORDING);
    ev_at(REC_IN_BLE_LOST, T0 + 1);
    ev_at(REC_IN_TICK, T0 + 1 + REC_BLE_GRACE_MS);
    ev_at(REC_IN_TICK, T0 + 2 + REC_BLE_GRACE_MS + REC_DRAIN_MS);
    TEST_ASSERT_EQUAL(REC_STATE_CLOSING, f.state);
    TEST_ASSERT_EQUAL(REC_OK, op_at(REC_CMD_STOP, T0 + 3 + REC_BLE_GRACE_MS + REC_DRAIN_MS).err);

    go(REC_STATE_RECORDING);
    ev_at(REC_IN_SHUTDOWN, T0 + 10);
    TEST_ASSERT_EQUAL(REC_OK, op_at(REC_CMD_STOP, T0 + 11).err);
    ev_at(REC_IN_AP_DOWN, T0 + 12);
    TEST_ASSERT_EQUAL(REC_OK, op_at(REC_CMD_STOP, T0 + 13).err);
    TEST_ASSERT_EQUAL_UINT8(REC_REASON_SHUTDOWN, f.reason);

    // A new session has had no take yet.
    TEST_ASSERT_EQUAL(REC_OK, op_at(REC_CMD_OPEN, T0 + 20).err);
    TEST_ASSERT_EQUAL(REC_ERR_STATE, op_at(REC_CMD_STOP, T0 + 21).err);
}

// What the glue does with the actions: carry them out in bit order while
// the take's sources go on pushing records until their action stops them.
typedef struct {
    int queued;                 // records in the ring
    int end_at;                 // ring index of END, -1 without
} sim_ring_t;

static sim_ring_t run_actions(uint32_t actions, bool audio, bool imu)
{
    sim_ring_t r = {.queued = 5, .end_at = -1};     // a backlog of the take
    for (uint32_t bit = 1; bit <= REC_ACT_NOTIFY; bit <<= 1) {
        switch (actions & bit) {
        case REC_ACT_AUDIO_OFF:   audio = false; break;
        // At a take start the tap starts, at a take end it stops, also when
        // the profile stays LEVEL (rec_fsm.h).
        case REC_ACT_SET_PROFILE: imu = f.state == REC_STATE_RECORDING; break;
        case REC_ACT_RING_RESET:  r.queued = 0; r.end_at = -1; break;
        case REC_ACT_RING_FLUSH:  r.queued = 0; r.end_at = -1; break;
        case REC_ACT_EMIT_END:    r.end_at = r.queued++; break;
        default: break;
        }
        // Between two actions a producer may push.
        r.queued += (audio ? 1 : 0) + (imu ? 1 : 0);
    }
    return r;
}

static void test_take_end_stops_its_sources_first(void)
{
    // The client is gone: nothing of the dead take may reach the next one.
    go(REC_STATE_RECORDING);
    sim_ring_t r = run_actions(ev_at(REC_IN_CLIENT_LOST, T0 + 10).actions, true, true);
    TEST_ASSERT_EQUAL_INT(0, r.queued);
    // A LEVEL take too: the request for LEVEL changes no profile but ends
    // the take's IMU records all the same.
    go(REC_STATE_READY);
    rec_cmd_t c = take_cmd();
    c.profile = REC_PROFILE_LEVEL;
    TEST_ASSERT_EQUAL(REC_OK, cmd_at(&c, T0 + 1).err);
    r = run_actions(ev_at(REC_IN_CLIENT_LOST, T0 + 10).actions, true, true);
    TEST_ASSERT_EQUAL_INT(0, r.queued);
    // Shutdown: END is the only thing left, and it is last.
    go(REC_STATE_RECORDING);
    r = run_actions(ev_at(REC_IN_SHUTDOWN, T0 + 10).actions, true, true);
    TEST_ASSERT_EQUAL_INT(1, r.queued);
    TEST_ASSERT_EQUAL_INT(0, r.end_at);
    // The drain limit and an empty ring at the stop: END comes last.
    env.ring_empty = true;
    go(REC_STATE_RECORDING);
    r = run_actions(op_at(REC_CMD_STOP, T0 + 10).actions, true, true);
    TEST_ASSERT_EQUAL_INT(r.queued - 1, r.end_at);
    // Every take end requests LEVEL.
    static const rec_input_kind_t ends[] = {REC_IN_CLIENT_LOST, REC_IN_SHUTDOWN};
    for (size_t i = 0; i < 2; i++) {
        go(REC_STATE_RECORDING);
        TEST_ASSERT_NOT_EQUAL(REC_PROFILE_LEVEL, rec_fsm_profile(&f));
        TEST_ASSERT_TRUE((ev_at(ends[i], T0 + 10).actions & SPR) != 0);
        TEST_ASSERT_EQUAL_UINT8(REC_PROFILE_LEVEL, rec_fsm_profile(&f));
    }
}

static void test_close_while_draining_closes_after_end(void)
{
    go(REC_STATE_DRAINING);
    TEST_ASSERT_EQUAL(REC_OK, op_at(REC_CMD_CLOSE, T0 + 1).err);
    TEST_ASSERT_EQUAL_HEX32(END, ev_at(REC_IN_DRAINED, T0 + 2).actions);
    TEST_ASSERT_EQUAL_HEX32(XAP | NTF, ev_at(REC_IN_DRAINED, T0 + 3).actions);
    TEST_ASSERT_EQUAL(REC_STATE_CLOSING, f.state);
    TEST_ASSERT_EQUAL_UINT8(REC_REASON_USER, f.reason);
}

// ---- take limits ------------------------------------------------------------------

static void test_max_s_stops_the_take(void)
{
    go(REC_STATE_RECORDING);
    TEST_ASSERT_EQUAL_HEX32(0, ev_at(REC_IN_TICK, T0 + 300000 - 1).actions);
    TEST_ASSERT_EQUAL_HEX32(AOFF | SPR | NTF, ev_at(REC_IN_TICK, T0 + 300000).actions);
    TEST_ASSERT_EQUAL(REC_STATE_DRAINING, f.state);
    TEST_ASSERT_EQUAL_UINT8(REC_REASON_MAX_S, f.reason);
    TEST_ASSERT_EQUAL_UINT16(300, rec_fsm_elapsed_s(&f, T0 + 400000));
}

static void test_low_battery_stops_the_take(void)
{
    go(REC_STATE_RECORDING);
    env.battery_pct = REC_BATT_STOP_PCT;
    TEST_ASSERT_EQUAL_HEX32(0, ev_at(REC_IN_TICK, T0 + 10).actions);
    env.battery_pct = REC_BATT_STOP_PCT - 1;
    env.vbus = true;
    TEST_ASSERT_EQUAL_HEX32(0, ev_at(REC_IN_TICK, T0 + 20).actions);
    env.vbus = false;
    TEST_ASSERT_EQUAL_HEX32(AOFF | SPR | NTF, ev_at(REC_IN_TICK, T0 + 30).actions);
    TEST_ASSERT_EQUAL_UINT8(REC_REASON_BATTERY, f.reason);
    // Low battery outside a take closes nothing by itself.
    go(REC_STATE_READY);
    TEST_ASSERT_EQUAL_HEX32(0, ev_at(REC_IN_TICK, T0 + 30).actions);
}

static void test_imu_lost_stops_the_take_after_5_s(void)
{
    go(REC_STATE_RECORDING);
    uint32_t t = T0 + 1000;
    rec_out_t o = ev_at(REC_IN_IMU_LOST, t);
    TEST_ASSERT_EQUAL_UINT16(REC_EV_IMU_LOST, o.event_code);
    // A second report does not restart the timer.
    TEST_ASSERT_EQUAL_HEX32(0, ev_at(REC_IN_IMU_LOST, t + 3000).actions);
    TEST_ASSERT_EQUAL_HEX32(0, ev_at(REC_IN_TICK, t + REC_IMU_LOST_MS - 1).actions);
    TEST_ASSERT_EQUAL_HEX32(AOFF | SPR | NTF, ev_at(REC_IN_TICK, t + REC_IMU_LOST_MS).actions);
    TEST_ASSERT_EQUAL_UINT8(REC_REASON_IMU, f.reason);

    // Back in time: the take goes on.
    go(REC_STATE_RECORDING);
    ev_at(REC_IN_IMU_LOST, t);
    o = ev_at(REC_IN_IMU_BACK, t + 4000);
    TEST_ASSERT_EQUAL_HEX32(EVT | NTF, o.actions);
    TEST_ASSERT_EQUAL_UINT16(REC_EV_IMU_BACK, o.event_code);
    TEST_ASSERT_EQUAL_HEX32(0, ev_at(REC_IN_TICK, t + 60000).actions);
    TEST_ASSERT_EQUAL(REC_STATE_RECORDING, f.state);
}

static void test_take_started_without_imu(void)
{
    go(REC_STATE_READY);
    ev_at(REC_IN_IMU_LOST, T0 + 10);
    // Long before the take: its timer starts with the take, which says so.
    uint32_t t = T0 + 600000;
    rec_out_t o = op_at(REC_CMD_TAKE_START, t);
    TEST_ASSERT_EQUAL_HEX32(SES | SPR | AON | EVT | NTF, o.actions);
    TEST_ASSERT_EQUAL_UINT16(REC_EV_IMU_LOST, o.event_code);
    TEST_ASSERT_EQUAL_HEX32(0, ev_at(REC_IN_TICK, t + REC_IMU_LOST_MS - 1).actions);
    ev_at(REC_IN_TICK, t + REC_IMU_LOST_MS);
    TEST_ASSERT_EQUAL_UINT8(REC_REASON_IMU, f.reason);
    // Also tracked while idle.
    rec_fsm_init(&f, T0);
    TEST_ASSERT_EQUAL_HEX32(0, ev_at(REC_IN_IMU_LOST, T0).actions);
    TEST_ASSERT_TRUE(f.imu_lost);
}

// ---- BLE grace ----------------------------------------------------------------------

static void test_ble_lost_take_goes_on_then_stops_and_closes(void)
{
    go(REC_STATE_RECORDING);
    uint32_t t = T0 + 2000;
    TEST_ASSERT_EQUAL_UINT16(REC_EV_BLE_LOST, ev_at(REC_IN_BLE_LOST, t).event_code);
    TEST_ASSERT_EQUAL_HEX32(0, ev_at(REC_IN_BLE_LOST, t + 10).actions);
    TEST_ASSERT_EQUAL_HEX32(0, ev_at(REC_IN_TICK, t + REC_BLE_GRACE_MS - 1).actions);
    TEST_ASSERT_EQUAL(REC_STATE_RECORDING, f.state);
    TEST_ASSERT_EQUAL_HEX32(AOFF | SPR | NTF, ev_at(REC_IN_TICK, t + REC_BLE_GRACE_MS).actions);
    TEST_ASSERT_EQUAL_UINT8(REC_REASON_BLE_LOST, f.reason);
    TEST_ASSERT_TRUE(f.close_pending);
    ev_at(REC_IN_DRAINED, t + REC_BLE_GRACE_MS + 1);
    TEST_ASSERT_EQUAL_HEX32(XAP | NTF, ev_at(REC_IN_DRAINED, t + REC_BLE_GRACE_MS + 2).actions);
    TEST_ASSERT_EQUAL(REC_STATE_CLOSING, f.state);
}

static void test_ble_back_in_time(void)
{
    go(REC_STATE_RECORDING);
    ev_at(REC_IN_BLE_LOST, T0 + 10);
    rec_out_t o = ev_at(REC_IN_BLE_BACK, T0 + 60000);
    TEST_ASSERT_EQUAL_HEX32(EVT, o.actions);
    TEST_ASSERT_EQUAL_UINT16(REC_EV_BLE_BACK, o.event_code);
    TEST_ASSERT_EQUAL_HEX32(0, ev_at(REC_IN_TICK, T0 + 10 + REC_BLE_GRACE_MS).actions);
    TEST_ASSERT_EQUAL(REC_STATE_RECORDING, f.state);
    // A new loss starts a new grace period.
    ev_at(REC_IN_BLE_LOST, T0 + 100000);
    TEST_ASSERT_EQUAL_HEX32(0, ev_at(REC_IN_TICK, T0 + 100000 + REC_BLE_GRACE_MS - 1).actions);
}

static void test_ble_grace_closes_a_session_without_take(void)
{
    static const rec_state_t states[] = {REC_STATE_AP_STARTING, REC_STATE_WAIT_CLIENT,
                                         REC_STATE_READY};
    for (size_t i = 0; i < 3; i++) {
        go(states[i]);
        ev_at(REC_IN_BLE_LOST, T0 + 1);
        // Keep the state's own timeout from firing first: refresh it.
        f.since_ms = T0 + REC_BLE_GRACE_MS;
        TEST_ASSERT_EQUAL_HEX32(XAP | NTF, ev_at(REC_IN_TICK, T0 + 1 + REC_BLE_GRACE_MS).actions);
        TEST_ASSERT_EQUAL(REC_STATE_CLOSING, f.state);
        TEST_ASSERT_EQUAL_UINT8(REC_REASON_BLE_LOST, f.reason);
    }
    // While draining it only marks the session to close.
    go(REC_STATE_DRAINING);
    ev_at(REC_IN_BLE_LOST, T0 + 1);
    f.since_ms = T0 + REC_BLE_GRACE_MS;
    TEST_ASSERT_EQUAL_HEX32(0, ev_at(REC_IN_TICK, T0 + 1 + REC_BLE_GRACE_MS).actions);
    TEST_ASSERT_TRUE(f.close_pending);
    TEST_ASSERT_EQUAL_UINT8(REC_REASON_USER, f.reason);
}

// ---- timeouts ---------------------------------------------------------------------------

static void check_timeout(rec_state_t s, uint32_t ms, uint8_t reason)
{
    go(s);
    TEST_ASSERT_EQUAL_HEX32(0, ev_at(REC_IN_TICK, T0 + ms - 1).actions);
    TEST_ASSERT_EQUAL_HEX32(XAP | NTF, ev_at(REC_IN_TICK, T0 + ms).actions);
    TEST_ASSERT_EQUAL(REC_STATE_CLOSING, f.state);
    TEST_ASSERT_EQUAL_UINT8(reason, f.reason);
}

static void test_session_timeouts(void)
{
    check_timeout(REC_STATE_AP_STARTING, REC_AP_START_MS, REC_REASON_AP_FAIL);
    check_timeout(REC_STATE_WAIT_CLIENT, REC_WAIT_CLIENT_MS, REC_REASON_NO_CLIENT);
    check_timeout(REC_STATE_READY, REC_READY_IDLE_MS, REC_REASON_IDLE);

    // CLOSING gives up waiting for AP_DOWN.
    go(REC_STATE_CLOSING);
    TEST_ASSERT_EQUAL_HEX32(0, ev_at(REC_IN_TICK, T0 + REC_CLOSE_MS - 1).actions);
    TEST_ASSERT_EQUAL_HEX32(NTF, ev_at(REC_IN_TICK, T0 + REC_CLOSE_MS).actions);
    TEST_ASSERT_EQUAL(REC_STATE_IDLE, f.state);

    // The READY idle time starts over after each take.
    go(REC_STATE_RECORDING);
    op_at(REC_CMD_STOP, T0 + 1000);
    ev_at(REC_IN_DRAINED, T0 + 1001);
    ev_at(REC_IN_DRAINED, T0 + 1002);
    TEST_ASSERT_EQUAL_HEX32(0, ev_at(REC_IN_TICK, T0 + 1002 + REC_READY_IDLE_MS - 1).actions);
    TEST_ASSERT_EQUAL_HEX32(XAP | NTF, ev_at(REC_IN_TICK, T0 + 1002 + REC_READY_IDLE_MS).actions);

    // A client that drops gets a fresh wait.
    go(REC_STATE_READY);
    ev_at(REC_IN_CLIENT_LOST, T0 + 50000);
    TEST_ASSERT_EQUAL_HEX32(0, ev_at(REC_IN_TICK, T0 + 50000 + REC_WAIT_CLIENT_MS - 1).actions);
    TEST_ASSERT_EQUAL_HEX32(XAP | NTF, ev_at(REC_IN_TICK, T0 + 50000 + REC_WAIT_CLIENT_MS).actions);
    TEST_ASSERT_EQUAL_UINT8(REC_REASON_NO_CLIENT, f.reason);
}

static void test_drain_limit(void)
{
    // Nothing drained in time: drop the rest, END, READY.
    go(REC_STATE_DRAINING);
    TEST_ASSERT_EQUAL_HEX32(0, ev_at(REC_IN_TICK, T0 + REC_DRAIN_MS - 1).actions);
    TEST_ASSERT_EQUAL_HEX32(REC_ACT_RING_FLUSH | END | NTF,
                            ev_at(REC_IN_TICK, T0 + REC_DRAIN_MS).actions);
    TEST_ASSERT_EQUAL(REC_STATE_READY, f.state);

    // END already queued: it is kept (it may still go out).
    go(REC_STATE_DRAINING);
    ev_at(REC_IN_DRAINED, T0 + 100);
    TEST_ASSERT_EQUAL_HEX32(NTF, ev_at(REC_IN_TICK, T0 + REC_DRAIN_MS).actions);
    TEST_ASSERT_EQUAL(REC_STATE_READY, f.state);

    // With a close pending the limit leads to CLOSING.
    go(REC_STATE_DRAINING);
    op_at(REC_CMD_CLOSE, T0 + 1);
    TEST_ASSERT_EQUAL_HEX32(REC_ACT_RING_FLUSH | END | XAP | NTF,
                            ev_at(REC_IN_TICK, T0 + REC_DRAIN_MS).actions);
    TEST_ASSERT_EQUAL(REC_STATE_CLOSING, f.state);
}

// ---- faults -------------------------------------------------------------------------------

static void test_client_lost_during_take(void)
{
    go(REC_STATE_RECORDING);
    rec_out_t o = ev_at(REC_IN_CLIENT_LOST, T0 + 7000);
    TEST_ASSERT_EQUAL_HEX32(RRST | AOFF | SPR | NTF, o.actions);
    TEST_ASSERT_EQUAL(REC_STATE_WAIT_CLIENT, f.state);
    TEST_ASSERT_EQUAL_UINT8(REC_REASON_CLIENT_LOST, f.reason);
    TEST_ASSERT_EQUAL_UINT8(REC_PROFILE_LEVEL, rec_fsm_profile(&f));
    TEST_ASSERT_EQUAL_UINT16(7, rec_fsm_elapsed_s(&f, T0 + 99000));
    // The client comes back: a new take can start.
    ev_at(REC_IN_HELLO_OK, T0 + 9000);
    TEST_ASSERT_EQUAL(REC_OK, op_at(REC_CMD_TAKE_START, T0 + 9500).err);
}

static void test_client_lost_while_draining_to_close(void)
{
    go(REC_STATE_RECORDING);
    op_at(REC_CMD_CLOSE, T0 + 1);
    TEST_ASSERT_EQUAL_HEX32(RRST | XAP | NTF, ev_at(REC_IN_CLIENT_LOST, T0 + 2).actions);
    TEST_ASSERT_EQUAL(REC_STATE_CLOSING, f.state);
}

static void test_shutdown(void)
{
    go(REC_STATE_RECORDING);
    ev_at(REC_IN_SHUTDOWN, T0 + 4000);
    TEST_ASSERT_EQUAL_UINT8(REC_REASON_SHUTDOWN, f.reason);
    TEST_ASSERT_EQUAL_UINT16(4, rec_fsm_elapsed_s(&f, T0 + 9000));
    // A take that already ended keeps its reason for END.
    go(REC_STATE_DRAINING);
    ev_at(REC_IN_SHUTDOWN, T0 + 10);
    TEST_ASSERT_EQUAL_UINT8(REC_REASON_USER, f.reason);
    // ... and an END already queued is not sent twice.
    go(REC_STATE_DRAINING);
    ev_at(REC_IN_DRAINED, T0 + 5);
    TEST_ASSERT_EQUAL_HEX32(XAP | NTF, ev_at(REC_IN_SHUTDOWN, T0 + 10).actions);
    // Without a take: the session's reason.
    go(REC_STATE_READY);
    ev_at(REC_IN_SHUTDOWN, T0 + 10);
    TEST_ASSERT_EQUAL_UINT8(REC_REASON_SHUTDOWN, f.reason);
}

static void test_ap_fail_reason(void)
{
    go(REC_STATE_AP_STARTING);
    ev_at(REC_IN_AP_FAIL, T0 + 3);
    TEST_ASSERT_EQUAL_UINT8(REC_REASON_AP_FAIL, f.reason);
}

static void test_timers_survive_the_ms_wrap(void)
{
    rec_fsm_init(&f, 0xFFFFF000u);
    TEST_ASSERT_EQUAL(REC_OK, op_at(REC_CMD_OPEN, 0xFFFFF000u).err);
    ev_at(REC_IN_AP_UP, 0xFFFFF000u);
    ev_at(REC_IN_HELLO_OK, 0xFFFFF000u);
    TEST_ASSERT_EQUAL(REC_OK, op_at(REC_CMD_TAKE_START, 0xFFFFF000u).err);
    uint32_t end = 0xFFFFF000u + 300000u;    // wraps
    TEST_ASSERT_EQUAL_UINT16(10, rec_fsm_elapsed_s(&f, 0xFFFFF000u + 10000u));
    TEST_ASSERT_EQUAL_HEX32(0, ev_at(REC_IN_TICK, end - 1).actions);
    TEST_ASSERT_EQUAL_HEX32(AOFF | SPR | NTF, ev_at(REC_IN_TICK, end).actions);
}

int main(void)
{
    UNITY_BEGIN();
    RUN_TEST(test_every_state_and_input);
    RUN_TEST(test_open_refused_while_ota_or_calibration);
    RUN_TEST(test_take_start_checks_its_arguments);
    RUN_TEST(test_take_defaults_and_no_audio);
    RUN_TEST(test_take_start_battery);
    RUN_TEST(test_take_start_refused_during_ota_or_calibration);
    RUN_TEST(test_mark_carries_its_code);
    RUN_TEST(test_bad_commands);
    RUN_TEST(test_session_with_two_takes);
    RUN_TEST(test_stop_with_an_empty_ring);
    RUN_TEST(test_stop_racing_the_take_end);
    RUN_TEST(test_take_end_stops_its_sources_first);
    RUN_TEST(test_close_while_draining_closes_after_end);
    RUN_TEST(test_max_s_stops_the_take);
    RUN_TEST(test_low_battery_stops_the_take);
    RUN_TEST(test_imu_lost_stops_the_take_after_5_s);
    RUN_TEST(test_take_started_without_imu);
    RUN_TEST(test_ble_lost_take_goes_on_then_stops_and_closes);
    RUN_TEST(test_ble_back_in_time);
    RUN_TEST(test_ble_grace_closes_a_session_without_take);
    RUN_TEST(test_session_timeouts);
    RUN_TEST(test_drain_limit);
    RUN_TEST(test_client_lost_during_take);
    RUN_TEST(test_client_lost_while_draining_to_close);
    RUN_TEST(test_shutdown);
    RUN_TEST(test_ap_fail_reason);
    RUN_TEST(test_timers_survive_the_ms_wrap);
    return UNITY_END();
}
