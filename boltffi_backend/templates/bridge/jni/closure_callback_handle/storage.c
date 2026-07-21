typedef struct {
    {{ handle.call_field }};
    void *context;
    void (*release)(void *);
} {{ handle.ty }};
