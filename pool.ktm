module pg;

/* A connection given back to its pool */
static void poolPut(void *pool, Conn *c)
{
    c.terminate();
    c.release_();
}
