module pg;
import str;

/* ---- Error codes ----
 * Errors the server sends carry their SQLSTATE in Error.code: STATE_TAG
 * and the five characters, one per byte (pg.state("23505") makes one).
 * Errors of the client have codes of their own. */

pub const i64 STATE_TAG = 0x5047 << 40;

pub const i64 ERR_CONNECT  = 0x70670001;  /* can't reach the server, or it refused the start-up */
pub const i64 ERR_AUTH     = 0x70670002;  /* authentication failed or isn't supported */
pub const i64 ERR_IO       = 0x70670003;  /* the connection broke: it is closed now */
pub const i64 ERR_NO_ROWS  = 0x70670004;  /* queryOne found no row */
pub const i64 ERR_SCAN     = 0x70670005;  /* a value that doesn't fit where it goes */
pub const i64 ERR_POOL     = 0x70670006;  /* no connection free in time, or the pool is closed */
pub const i64 ERR_PROTOCOL = 0x70670007;  /* the server sent something unexpected */
pub const i64 ERR_ARG      = 0x70670008;  /* a parameter that can't be sent */
pub const i64 ERR_CLOSED   = 0x70670009;  /* a closed connection, rows or transaction was used */
pub const i64 ERR_TLS      = 0x7067000A;  /* TLS failed: the handshake, the certificate, or the server has none */

/* SQLSTATE "abcde" as an Error.code */
pub i64 state(u8[] s)
{
    if (s.len != 5) { return 0; }
    return STATE_TAG | (i64)s[0] << 32 | (i64)s[1] << 24 | (i64)s[2] << 16 | (i64)s[3] << 8 | s[4];
}

/* Whether an Error.code came from the server (it is a SQLSTATE) */
pub bool isServerError(i64 code)
{
    return (code >> 40) == 0x5047;
}

/* The SQLSTATE of a server error's code ("23505"), "" for a client
 * one: a String that is yours to free */
pub String stateText(i64 code)
{
    String s = str.new();
    if ((code >> 40) != 0x5047) { return s; }
    for (i64 i = 0; i < 5; i++) { s.appendByte((code >> ((4 - i) * 8)) & 0xFF); }
    return s;
}

/* SQLSTATEs code often checks for */
pub const i64 UNIQUE_VIOLATION      = STATE_TAG | 0x3233353035;  /* 23505 */
pub const i64 FOREIGN_KEY_VIOLATION = STATE_TAG | 0x3233353033;  /* 23503 */
pub const i64 NOT_NULL_VIOLATION    = STATE_TAG | 0x3233353032;  /* 23502 */
pub const i64 CHECK_VIOLATION       = STATE_TAG | 0x3233353134;  /* 23514 */
pub const i64 SERIALIZATION_FAILURE = STATE_TAG | 0x3430303031;  /* 40001 */
pub const i64 DEADLOCK_DETECTED     = STATE_TAG | 0x3430503031;  /* 40P01 */
pub const i64 UNDEFINED_TABLE       = STATE_TAG | 0x3432503031;  /* 42P01 */
pub const i64 UNDEFINED_COLUMN      = STATE_TAG | 0x3432373033;  /* 42703 */
pub const i64 SYNTAX_ERROR          = STATE_TAG | 0x3432363031;  /* 42601 */
pub const i64 QUERY_CANCELED        = STATE_TAG | 0x3537303134;  /* 57014 */
pub const i64 INVALID_PASSWORD      = STATE_TAG | 0x3238503031;  /* 28P01 */
pub const i64 DIVISION_BY_ZERO      = STATE_TAG | 0x3232303132;  /* 22012 */
pub const i64 IN_FAILED_TRANSACTION = STATE_TAG | 0x3235503032;  /* 25P02 */
