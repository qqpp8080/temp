@Plugin(name = "WrappedLayout",
        category = Node.CATEGORY,
        elementType = Layout.ELEMENT_TYPE)
public class WrappedLayout extends AbstractStringLayout {

    private static final StatusLogger STATUS_LOG = StatusLogger.getLogger();

    private static final int MAX_CAUSE_DEPTH = 50;

    private static final Set<Class<? extends Throwable>> SENSITIVE_EXCEPTIONS = Set.of(
            FileNotFoundException.class,
            AccessDeniedException.class
    );

    /**
     * 构造器缓存
     */
    private static final Map<Class<? extends Throwable>, Constructor<? extends Throwable>> CTOR_CACHE =
            new ConcurrentHashMap<>();

    /**
     * 预编译异常字符串脱敏正则
     */
    private static final Map<Pattern, String> SENSITIVE_EXCEPTION_PATTERNS;

    static {
        Map<Pattern, String> map = new LinkedHashMap<>();

        for (Class<? extends Throwable> cls : SENSITIVE_EXCEPTIONS) {

            String fqcn = Pattern.quote(cls.getName());
            String simple = Pattern.quote(cls.getSimpleName());

            Pattern p = Pattern.compile(
                    "(" + fqcn + "|" + simple + "):\\s*(.*?)(?=\\n\\s*at\\s|$)",
                    Pattern.DOTALL
            );

            map.put(p, "$1: ***");
        }

        SENSITIVE_EXCEPTION_PATTERNS = Collections.unmodifiableMap(map);
    }

    private final MyPatternLayout sdkLayout;

    private WrappedLayout(MyPatternLayout sdkLayout, Charset charset) {
        super(charset, null, null);
        this.sdkLayout = sdkLayout;
    }

    @PluginFactory
    public static WrappedLayout createLayout(
            @PluginAttribute("pattern") String pattern,
            @PluginAttribute(value = "charset", defaultString = "UTF-8") Charset charset,
            @PluginConfiguration Configuration config) {

        MyPatternLayout sdk = MyPatternLayout.createLayout(pattern, charset, config);
        return new WrappedLayout(sdk, charset);
    }

    @Override
    public String toSerializable(LogEvent event) {

        try {

            Log4jLogEvent.Builder builder = null;

            // =====================================================
            // 1. Throwable 处理（只在需要时才递归）
            // =====================================================

            Throwable thrown = event.getThrown();

            if (thrown != null && containsSensitiveException(thrown)) {

                Throwable masked = maskThrowable(thrown, 0);

                if (masked != thrown) {
                    builder = Log4jLogEvent.newBuilder(event);
                    builder.setThrown(masked);
                }
            }

            // =====================================================
            // 2. Message 处理（避免不必要的字符串操作）
            // =====================================================

            Message msg = event.getMessage();

            if (msg != null) {

                String raw = msg.getFormattedMessage();

                if (raw != null && raw.indexOf(':') > 0) {

                    String masked = maskSensitiveInString(raw);

                    if (!masked.equals(raw)) {

                        if (builder == null) {
                            builder = Log4jLogEvent.newBuilder(event);
                        }

                        builder.setMessage(new SimpleMessage(masked));
                    }
                }
            }

            // =====================================================
            // 3. 只创建一次新 LogEvent
            // =====================================================

            if (builder != null) {
                event = builder.build();
            }

            return sdkLayout.toSerializable(event);

        } catch (Exception e) {

            STATUS_LOG.warn("WrappedLayout failed, fallback", e);

            try {
                return sdkLayout.toSerializable(event);
            } catch (Exception ex) {
                return event.getMessage().getFormattedMessage() + "\n";
            }
        }
    }

    // =========================================================
    // 是否包含敏感异常（先判断，避免无意义递归）
    // =========================================================

    private boolean containsSensitiveException(Throwable t) {

        int depth = 0;

        while (t != null && depth < MAX_CAUSE_DEPTH) {

            for (Class<? extends Throwable> cls : SENSITIVE_EXCEPTIONS) {
                if (cls.isInstance(t)) {
                    return true;
                }
            }

            t = t.getCause();
            depth++;
        }

        return false;
    }

    // =========================================================
    // Throwable 脱敏
    // =========================================================

    private Throwable maskThrowable(Throwable original, int depth) {

        if (original == null || depth > MAX_CAUSE_DEPTH) {
            return original;
        }

        try {

            boolean needMask = false;

            for (Class<? extends Throwable> cls : SENSITIVE_EXCEPTIONS) {
                if (cls.isInstance(original)) {
                    needMask = true;
                    break;
                }
            }

            Throwable cause = original.getCause();
            Throwable maskedCause = maskThrowable(cause, depth + 1);

            if (!needMask && maskedCause == cause) {
                return original;
            }

            String newMessage = needMask ? "***" : original.getMessage();

            return rebuildThrowable(original, newMessage, maskedCause);

        } catch (Exception e) {
            return original;
        }
    }

    private Throwable rebuildThrowable(Throwable original,
                                        String newMessage,
                                        Throwable newCause) {

        try {

            Throwable rebuilt = createThrowableInstance(original.getClass(), newMessage, newCause);

            rebuilt.setStackTrace(original.getStackTrace());

            if (rebuilt.getCause() == null && newCause != null) {
                rebuilt.initCause(newCause);
            }

            return rebuilt;

        } catch (Exception e) {
            return original;
        }
    }

    private Throwable createThrowableInstance(Class<? extends Throwable> type,
                                              String message,
                                              Throwable cause) {

        try {

            Constructor<? extends Throwable> ctor = CTOR_CACHE.computeIfAbsent(type, t -> {

                try {
                    return t.getConstructor(String.class, Throwable.class);
                } catch (NoSuchMethodException e1) {
                    try {
                        return t.getConstructor(String.class);
                    } catch (NoSuchMethodException e2) {
                        return null;
                    }
                }
            });

            if (ctor != null) {
                if (ctor.getParameterCount() == 2) {
                    return ctor.newInstance(message, cause);
                } else {
                    return ctor.newInstance(message);
                }
            }

        } catch (Exception ignore) {
        }

        return new RuntimeException(message, cause);
    }

    // =========================================================
    // 字符串脱敏
    // =========================================================

    private String maskSensitiveInString(String message) {

        String result = message;

        for (Map.Entry<Pattern, String> entry : SENSITIVE_EXCEPTION_PATTERNS.entrySet()) {
            result = entry.getKey().matcher(result).replaceAll(entry.getValue());
        }

        return result;
    }
}
