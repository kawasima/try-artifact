package net.unit8.jetshell.tool;

import jdk.jshell.*;
import jdk.jshell.Snippet.Status;
import jdk.jshell.Snippet.SubKind;
import jdk.jshell.SourceCodeAnalysis.CompletionInfo;
import jdk.jshell.SourceCodeAnalysis.Suggestion;

import org.jline.reader.*;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.prefs.Preferences;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Command line REPL tool for Java using the JShell API.
 *
 * @author kawasima
 */
public class JetShellTool {

    final InputStream cmdin;
    final PrintStream cmdout;
    final PrintStream cmderr;
    final PrintStream console;
    final InputStream userin;
    final PrintStream userout;
    final PrintStream usererr;

    private JShell state;
    private SourceCodeAnalysis analysis;
    private boolean live = false;
    private boolean regenerateOnDeath = true;

    private String cmdlineClasspath = null;
    private String cmdlineStartup = null;

    private List<String> replayableHistory;
    private List<String> replayableHistoryPrevious;
    private Set<String> startupSnippetIds;
    private boolean suppressOutput = false;
    private boolean hadFailure = false;

    // A sealed interface/class whose permitted subtypes are declared on later lines
    // cannot compile as its own JShell compilation unit (no permits, no same-unit
    // subtypes). We defer such a declaration, collect the contiguous subtype
    // declarations that follow, then synthesise an explicit permits clause so the
    // whole hierarchy compiles -- the same way it would as a single .java file.
    private String pendingSealedHeader;
    private String pendingSealedName;
    private final List<String> pendingSubtypeSources = new ArrayList<>();
    private final List<String> pendingSubtypeNames = new ArrayList<>();

    public boolean testPrompt = false;

    boolean hadFailure() {
        return hadFailure;
    }

    static final Preferences PREFS = Preferences.userRoot().node("tool/JetShell");
    private static final String STARTUP_KEY = "STARTUP";
    private static final String GLOB_CHARS = "*?{[";
    // Sentinel returned by processCommandArgs when -help or -version is handled (normal early exit)
    private static final List<String> EARLY_EXIT = Collections.emptyList();

    static final String DEFAULT_STARTUP =
            "\n" +
            "import java.util.*;\n" +
            "import java.io.*;\n" +
            "import java.math.*;\n" +
            "import java.net.*;\n" +
            "import java.util.concurrent.*;\n" +
            "import java.util.prefs.*;\n" +
            "import java.util.regex.*;\n" +
            "void printf(String format, Object... args) { System.out.printf(format, args); }\n";

    // Command infrastructure
    private final Map<String, Command> commands = new LinkedHashMap<>();

    /**
     * @param cmdin command line input -- snippets and commands
     * @param cmdout command line output, feedback including errors
     * @param cmderr start-up errors and debugging info
     * @param console console control interaction
     * @param userin code execution input
     * @param userout code execution output  -- System.out.printf("hi")
     * @param usererr code execution error stream  -- System.err.printf("Oops")
     */
    public JetShellTool(InputStream cmdin, PrintStream cmdout, PrintStream cmderr,
                         PrintStream console,
                         InputStream userin, PrintStream userout, PrintStream usererr) {
        this.cmdin = cmdin;
        this.cmdout = cmdout;
        this.cmderr = cmderr;
        this.console = console;
        this.userin = userin;
        this.userout = userout;
        this.usererr = usererr;
        registerBuiltinCommands();
    }

    /**
     * Creates a JetShellTool wired to standard I/O streams.
     * Command I/O, user code I/O, and the console all share the same streams.
     */
    public static JetShellTool create(InputStream in, PrintStream out, PrintStream err) {
        return new JetShellTool(in, out, err, out, in, out, err);
    }

    public JShell getState() {
        return state;
    }

    // --- Output helpers ---

    public void hard(String format, Object... args) {
        if (!suppressOutput) {
            cmdout.printf("|  " + format + "%n", args);
        }
    }

    public void error(String format, Object... args) {
        // Errors are always shown, even during startup (suppressOutput only mutes normal output)
        cmderr.printf("|  " + format + "%n", args);
    }

    public void fluff(String format, Object... args) {
        hard(format, args);
    }

    // --- Command registration ---

    @FunctionalInterface
    public interface CommandHandler {
        boolean handle(String arg);
    }

    public interface CompletionProvider {
        List<Suggestion> completionSuggestions(String input, int cursor, int[] anchor);
    }

    public enum CommandKind {
        NORMAL(true, true, true),
        REPLAY(true, true, true),
        HIDDEN(true, false, false);

        final boolean isRealCommand;
        final boolean showInHelp;
        final boolean shouldSuggestCompletions;

        CommandKind(boolean isRealCommand, boolean showInHelp, boolean shouldSuggestCompletions) {
            this.isRealCommand = isRealCommand;
            this.showInHelp = showInHelp;
            this.shouldSuggestCompletions = shouldSuggestCompletions;
        }
    }

    public static class Command {
        public final String command;
        public final String params;
        public final String description;
        public final String help;
        public final CommandHandler run;
        public final CompletionProvider completions;
        public final CommandKind kind;

        private static final CompletionProvider EMPTY = (input, cursor, anchor) -> Collections.emptyList();

        public Command(String command, String params, String description, String help,
                       CommandHandler run, CompletionProvider completions, CommandKind kind) {
            this.command = command;
            this.params = params;
            this.description = description;
            this.help = help;
            this.run = run;
            this.completions = completions;
            this.kind = kind;
        }

        public Command(String command, String params, String description, String help,
                       CommandHandler run, CommandKind kind) {
            this(command, params, description, help, run, EMPTY, kind);
        }
    }

    public void registerCommand(Command cmd) {
        commands.put(cmd.command, cmd);
    }

    // --- Startup ---

    public int start(String[] args) throws Exception {
        List<String> loadList = processCommandArgs(args);
        if (loadList == null) {
            return 1; // argument error
        }
        if (loadList == EARLY_EXIT) {
            return 0; // -help or -version: normal early exit
        }

        if (testPrompt) {
            startWithTestPrompt(loadList);
            return hadFailure ? 1 : 0;
        } else {
            boolean batchMode = startInteractive(loadList);
            return batchMode && hadFailure ? 1 : 0;
        }
    }

    // Returns true if running in batch (non-TTY) mode, false if interactive
    private boolean startInteractive(List<String> loadList) throws Exception {
        Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .build();

        Completer completer = (reader, line, candidates) -> {
            String text = line.line();
            int cursor = line.cursor();
            int[] anchor = new int[]{-1};

            // Run completion with a spinner for potentially slow operations
            List<Suggestion> suggestions = completeWithSpinner(terminal, text, cursor, anchor);

            // jline3 replaces word() with the candidate value.
            // word() is determined by the parser (default: space-delimited).
            // JShell's anchor marks the start of the text to complete.
            // We prepend the text between word-start and anchor so that
            // jline3's replacement produces the correct result.
            int anchorPos = anchor[0] >= 0 ? anchor[0] : cursor;
            int wordStart = line.cursor() - line.wordCursor();
            String gap = anchorPos > wordStart
                    ? text.substring(wordStart, anchorPos) : "";
            suggestions.stream()
                    .map(Suggestion::continuation)
                    .distinct()
                    .forEach(c -> candidates.add(new Candidate(
                            gap + c, c, null, null, "", null, false)));
        };

        org.jline.reader.impl.DefaultParser parser = new org.jline.reader.impl.DefaultParser();
        // Use empty array (not null) to avoid both escaping and quoting.
        // null escapeChars causes DefaultParser.ArgumentList.escape() to
        // wrap candidates containing spaces in single quotes.
        parser.setEscapeChars(new char[0]);
        parser.setQuoteChars(new char[0]);

        java.nio.file.Path historyFile = java.nio.file.Paths.get(
                System.getProperty("user.home"), ".jetshell_history");
        LineReader lineReader = LineReaderBuilder.builder()
                .appName("JetShell")
                .terminal(terminal)
                .completer(completer)
                .parser(parser)
                .variable(LineReader.HISTORY_FILE, historyFile)
                .build();
        lineReader.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);

        // JLine sets terminal type to "dumb" when stdin is not a TTY (piped/batch mode)
        boolean batchMode = "dumb".equalsIgnoreCase(terminal.getType());
        try {
            try {
                resetState(loadList);
            } catch (Exception e) {
                hard("Failed to initialize: %s", e.getMessage());
                hadFailure = true;
                return batchMode;
            }
            hard("Welcome to JetShell -- Version %s", version());
            hard("Type /help for help");

            while (regenerateOnDeath) {
                if (!live) {
                    resetState(loadList);
                }
                runInteractive(lineReader);
            }
        } finally {
            closeState();
            terminal.close();
        }
        return batchMode;
    }

    private void startWithTestPrompt(List<String> loadList) {
        resetState(loadList);
        hard("Welcome to JetShell -- Version %s", version());
        hard("Type /help for help");

        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(cmdin));
            while (regenerateOnDeath) {
                if (!live) {
                    resetState(loadList);
                }
                runWithReader(reader);
            }
        } catch (IOException ex) {
            hard("Unexpected exception: %s", ex);
            hadFailure = true;
        } finally {
            closeState();
        }
    }

    private void runInteractive(LineReader lineReader) {
        String incomplete = "";
        try {
            while (live) {
                String prompt = incomplete.isEmpty() ? "\n-> " : ">> ";
                String raw;
                try {
                    raw = lineReader.readLine(prompt);
                } catch (UserInterruptException ex) {
                    incomplete = "";
                    continue;
                } catch (EndOfFileException ex) {
                    regenerateOnDeath = false;
                    break;
                }
                incomplete = processInput(raw, incomplete);
            }
            flushPendingSealed();
        } catch (Exception ex) {
            hard("Unexpected exception: %s", ex);
            hadFailure = true;
        }
    }

    private void runWithReader(BufferedReader reader) throws IOException {
        String incomplete = "";
        while (live) {
            String prompt = incomplete.isEmpty() ? "\u0005" : "\u0006";
            console.print(prompt);
            console.flush();

            String raw = reader.readLine();
            if (raw == null) {
                regenerateOnDeath = false;
                break;
            }
            incomplete = processInput(raw, incomplete);
        }
        flushPendingSealed();
    }

    private String processInput(String raw, String incomplete) {
        if (raw == null) {
            regenerateOnDeath = false;
            return "";
        }
        // Strip only trailing whitespace to preserve leading spaces in source code
        // (e.g. indentation in text blocks and multiline expressions).
        String stripped = raw.stripTrailing();

        // When there is no incomplete input yet, check if this line is a command.
        // Use stripLeading() so a slash preceded by spaces is still recognized as a command.
        if (incomplete.isEmpty()) {
            String commandCandidate = stripped.stripLeading();
            if (commandCandidate.startsWith("/") &&
                    !commandCandidate.startsWith("//") &&
                    !commandCandidate.startsWith("/*")) {
                processCommand(commandCandidate.trim());
                return "";
            }
        }

        // Ignore whitespace-only lines when there is nothing to continue.
        if (stripped.isEmpty() && incomplete.isEmpty()) {
            return "";
        }

        String combined = incomplete.isEmpty() ? stripped : incomplete + "\n" + stripped;
        return processSourceCatchingReset(combined);
    }

    // --- State management ---

    private void resetState(List<String> loadList) {
        closeState();
        pendingSealedHeader = null;
        pendingSealedName = null;
        pendingSubtypeSources.clear();
        pendingSubtypeNames.clear();
        replayableHistoryPrevious = replayableHistory;
        replayableHistory = new ArrayList<>();

        state = JShell.builder()
                .in(userin)
                .out(userout)
                .err(usererr)
                .executionEngine("local")
                .build();
        analysis = state.sourceCodeAnalysis();

        state.onShutdown(deadState -> {
            if (deadState == state) {
                hard("State engine terminated.");
                hard("Restore definitions with: /reload restore");
                live = false;
            }
        });
        live = true;

        if (cmdlineClasspath != null) {
            state.addToClasspath(cmdlineClasspath);
        }

        String start = cmdlineStartup != null ? cmdlineStartup
                : PREFS.get(STARTUP_KEY, DEFAULT_STARTUP);
        if (!start.isBlank()) {
            suppressOutput = true;
            processSource(start);
            flushPendingSealed();
            suppressOutput = false;
            // Record snippet IDs that belong to startup so /list start can filter them
            startupSnippetIds = state.snippets()
                    .map(Snippet::id)
                    .collect(Collectors.toCollection(HashSet::new));
        } else {
            startupSnippetIds = new HashSet<>();
        }

        for (String loadFile : loadList) {
            cmdOpen(loadFile);
        }
    }

    private void closeState() {
        live = false;
        if (state != null) {
            state.close();
            state = null;
        }
    }

    // --- Source processing ---

    private String processSourceCatchingReset(String src) {
        try {
            return processSource(src);
        } catch (IllegalStateException ex) {
            hard("Resetting...");
            live = false;
            return "";
        }
    }

    private String processSource(String srcInput) {
        while (true) {
            CompletionInfo an = analysis.analyzeCompletion(srcInput);
            if (!an.completeness().isComplete()) {
                return an.remaining();
            }
            boolean failed = routeCompleteSource(an.source());
            if (failed || an.remaining().isEmpty()) {
                return "";
            }
            srcInput = an.remaining();
        }
    }

    // Routes a complete snippet, deferring a sealed-type hierarchy so an explicit
    // permits clause can be synthesised once its subtypes are known (see Issue #17).
    private boolean routeCompleteSource(String source) {
        if (pendingSealedName != null) {
            String subtypeName = subtypeNameIfExtends(source, pendingSealedName);
            if (subtypeName != null) {
                pendingSubtypeSources.add(source);
                pendingSubtypeNames.add(subtypeName);
                return false;
            }
            // The contiguous hierarchy ended; finalise it, then route this snippet
            // afresh (it may itself open a new sealed hierarchy).
            boolean failed = flushPendingSealed();
            return routeCompleteSource(source) || failed;
        }
        String sealedName = sealedTypeNameWithoutPermits(source);
        if (sealedName != null) {
            pendingSealedHeader = source;
            pendingSealedName = sealedName;
            return false;
        }
        return processCompleteSource(source);
    }

    // Evaluates a deferred sealed declaration with a synthesised permits clause,
    // followed by its collected subtypes. No-op when nothing is pending.
    private boolean flushPendingSealed() {
        if (pendingSealedName == null) {
            return false;
        }
        String header = pendingSealedHeader;
        List<String> subtypeSources = new ArrayList<>(pendingSubtypeSources);
        List<String> subtypeNames = new ArrayList<>(pendingSubtypeNames);
        pendingSealedHeader = null;
        pendingSealedName = null;
        pendingSubtypeSources.clear();
        pendingSubtypeNames.clear();

        if (subtypeNames.isEmpty()) {
            // No subtypes were found; evaluate the original as-is so JShell reports
            // the genuine error, matching plain jshell behaviour.
            return processCompleteSource(header);
        }
        // Evaluate the sealed type with its synthesised permits clause. This is the
        // form JShell records as the snippet source, so /list, /save and /reload all
        // show the same self-contained, re-runnable declaration.
        String sealedWithPermits = injectPermits(header, subtypeNames);
        boolean failed = processCompleteSource(sealedWithPermits);
        for (String subtypeSource : subtypeSources) {
            failed |= processCompleteSource(subtypeSource);
        }
        return failed;
    }

    private boolean processCompleteSource(String source) {
        boolean failed = false;
        boolean isActive = false;
        List<SnippetEvent> events = state.eval(source);
        for (SnippetEvent e : events) {
            boolean eventFailed = handleEvent(e);
            failed |= eventFailed;
            hadFailure |= eventFailed;
            isActive |= e.causeSnippet() == null
                    && e.status().isActive()
                    && e.snippet().subKind() != SubKind.VAR_VALUE_SUBKIND;
        }
        if (isActive && live) {
            replayableHistory.add(source);
        }
        return failed;
    }

    // --- Sealed-type deferral helpers (Issue #17) ---

    private static final java.util.regex.Pattern TYPE_NAME =
            java.util.regex.Pattern.compile("\\b(?:class|interface|record|enum)\\s+([A-Za-z_$][\\w$]*)");
    private static final java.util.regex.Pattern SEALED_KW = java.util.regex.Pattern.compile("\\bsealed\\b");
    private static final java.util.regex.Pattern PERMITS_KW = java.util.regex.Pattern.compile("\\bpermits\\b");
    private static final java.util.regex.Pattern SUPERTYPE_KW =
            java.util.regex.Pattern.compile("\\b(?:extends|implements)\\b");

    private static String declHeader(String source) {
        int brace = source.indexOf('{');
        return brace >= 0 ? source.substring(0, brace) : source;
    }

    // Returns the declared type name if the snippet is a `sealed` interface/class
    // with no explicit `permits` clause; otherwise null.
    private static String sealedTypeNameWithoutPermits(String source) {
        String header = declHeader(source);
        if (!SEALED_KW.matcher(header).find() || PERMITS_KW.matcher(header).find()) {
            return null;
        }
        java.util.regex.Matcher m = TYPE_NAME.matcher(header);
        return m.find() ? m.group(1) : null;
    }

    // Returns the declared type name if the snippet extends/implements `superName`
    // (so it belongs in that sealed type's permits clause); otherwise null.
    private static String subtypeNameIfExtends(String source, String superName) {
        String header = declHeader(source);
        java.util.regex.Matcher kw = SUPERTYPE_KW.matcher(header);
        if (!kw.find() || !containsWord(header.substring(kw.start()), superName)) {
            return null;
        }
        java.util.regex.Matcher m = TYPE_NAME.matcher(header);
        return m.find() ? m.group(1) : null;
    }

    // Inserts `permits A, B, ...` immediately before the type body.
    private static String injectPermits(String header, List<String> subtypeNames) {
        int brace = header.indexOf('{');
        if (brace < 0) {
            return header;
        }
        String beforeBody = header.substring(0, brace).stripTrailing();
        String body = header.substring(brace);
        return beforeBody + " permits " + String.join(", ", subtypeNames) + " " + body;
    }

    private static boolean containsWord(String text, String word) {
        return java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(word) + "\\b")
                .matcher(text).find();
    }

    private boolean handleEvent(SnippetEvent ste) {
        Snippet sn = ste.snippet();
        if (sn == null) {
            return false;
        }
        List<Diag> diagnostics = state.diagnostics(sn).collect(Collectors.toList());
        String source = sn.source();

        if (ste.causeSnippet() == null) {
            // Main event
            printDiagnostics(source, diagnostics);
            if (ste.status().isActive()) {
                if (ste.exception() != null) {
                    if (ste.exception() instanceof EvalException) {
                        printEvalException((EvalException) ste.exception());
                        return true;
                    } else if (ste.exception() instanceof UnresolvedReferenceException) {
                        printUnresolved((UnresolvedReferenceException) ste.exception());
                    } else {
                        hard("Unexpected execution exception: %s", ste.exception());
                        return true;
                    }
                } else {
                    displayResult(ste);
                }
            } else if (ste.status() == Status.REJECTED) {
                if (diagnostics.isEmpty()) {
                    hard("Failed.");
                }
                return true;
            }
        } else if (ste.status() == Status.REJECTED) {
            if (sn instanceof DeclarationSnippet) {
                hard("Caused failure of dependent %s", ((DeclarationSnippet) sn).name());
            }
            printDiagnostics(source, diagnostics);
            return true;
        } else {
            // Update event
            if (sn instanceof DeclarationSnippet) {
                displayResult(ste);
            }
        }
        return false;
    }

    private void displayResult(SnippetEvent ste) {
        Snippet sn = ste.snippet();
        Status status = ste.status();
        boolean update = ste.causeSnippet() != null;
        String value = ste.value();

        String action = resolveAction(ste, update);
        if (action == null) {
            return;
        }
        String prefix = update ? "  Update " : "";

        switch (sn.subKind()) {
            case CLASS_SUBKIND:
                hard("%s%s class %s", prefix, action, ((TypeDeclSnippet) sn).name());
                break;
            case INTERFACE_SUBKIND:
                hard("%s%s interface %s", prefix, action, ((TypeDeclSnippet) sn).name());
                break;
            case ENUM_SUBKIND:
                hard("%s%s enum %s", prefix, action, ((TypeDeclSnippet) sn).name());
                break;
            case ANNOTATION_TYPE_SUBKIND:
                hard("%s%s annotation interface %s", prefix, action, ((TypeDeclSnippet) sn).name());
                break;
            case METHOD_SUBKIND:
                hard("%s%s method %s(%s)", prefix, action,
                        ((MethodSnippet) sn).name(), ((MethodSnippet) sn).parameterTypes());
                break;
            case VAR_DECLARATION_SUBKIND: {
                VarSnippet vk = (VarSnippet) sn;
                hard("%s%s variable %s of type %s", prefix, action, vk.name(), vk.typeName());
                break;
            }
            case VAR_DECLARATION_WITH_INITIALIZER_SUBKIND: {
                VarSnippet vk = (VarSnippet) sn;
                hard("%s%s variable %s of type %s with initial value %s",
                        prefix, action, vk.name(), vk.typeName(), value);
                break;
            }
            case TEMP_VAR_EXPRESSION_SUBKIND: {
                VarSnippet vk = (VarSnippet) sn;
                hard("Expression value is: %s", value);
                hard("  assigned to temporary variable %s of type %s", vk.name(), vk.typeName());
                break;
            }
            case VAR_VALUE_SUBKIND: {
                ExpressionSnippet ek = (ExpressionSnippet) sn;
                hard("Variable %s of type %s has value %s", ek.name(), ek.typeName(), value);
                break;
            }
            case ASSIGNMENT_SUBKIND: {
                ExpressionSnippet ek = (ExpressionSnippet) sn;
                hard("Variable %s has been assigned the value %s", ek.name(), value);
                break;
            }
            default:
                break;
        }

        if (sn instanceof DeclarationSnippet && (status == Status.RECOVERABLE_DEFINED || status == Status.RECOVERABLE_NOT_DEFINED)) {
            hard("  however, it cannot be %s until %s is declared",
                    status == Status.RECOVERABLE_NOT_DEFINED ? "referenced" : "invoked",
                    unresolved((DeclarationSnippet) sn));
        }
    }

    private String resolveAction(SnippetEvent ste, boolean update) {
        switch (ste.status()) {
            case VALID:
            case RECOVERABLE_DEFINED:
            case RECOVERABLE_NOT_DEFINED:
                if (ste.previousStatus().isActive()) {
                    return ste.isSignatureChange() ? "Replaced" : "Modified";
                }
                return "Added";
            case OVERWRITTEN:
                return "Overwrote";
            case DROPPED:
                return "Dropped";
            case REJECTED:
                return "Rejected";
            default:
                return null;
        }
    }

    private void printDiagnostics(String source, List<Diag> diagnostics) {
        for (Diag diag : diagnostics) {
            if (diag.isError()) {
                hard("Error:");
            }
            for (String line : diag.getMessage(Locale.getDefault()).split("\\R")) {
                hard("%s", line);
            }
            int startPos = (int) diag.getStartPosition();
            int endPos = (int) diag.getEndPosition();
            // Use a regex matcher to correctly track line-separator lengths (\r\n, \n, \r).
            java.util.regex.Matcher lineMatcher = java.util.regex.Pattern.compile("\\R").matcher(source);
            int pos = 0;
            int searchFrom = 0;
            while (true) {
                int lineEnd = lineMatcher.find(searchFrom) ? lineMatcher.start() : source.length();
                String srcLine = source.substring(pos, lineEnd);
                if (startPos >= pos && startPos <= lineEnd) {
                    hard("%s", srcLine);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < srcLine.length(); i++) {
                        sb.append(i >= (startPos - pos) && i < (endPos - pos) ? '^' : ' ');
                    }
                    hard("%s", sb.toString());
                    break;
                }
                if (lineEnd == source.length()) break;
                searchFrom = lineMatcher.end();
                pos = lineMatcher.end();
            }
        }
    }

    private void printEvalException(EvalException ex) {
        hard("Exception %s: %s", ex.getExceptionClassName(), ex.getMessage());
        for (StackTraceElement ste : ex.getStackTrace()) {
            StringBuilder sb = new StringBuilder();
            String cn = ste.getClassName();
            if (!cn.isEmpty()) {
                int dot = cn.lastIndexOf('.');
                if (dot > 0) {
                    cn = cn.substring(dot + 1);
                }
                sb.append(cn).append(".");
            }
            if (!ste.getMethodName().isEmpty()) {
                sb.append(ste.getMethodName());
            }
            hard("    at %s(%s:%d)", sb, ste.getFileName(), ste.getLineNumber());
        }
    }

    private void printUnresolved(UnresolvedReferenceException ex) {
        DeclarationSnippet sn = ex.getSnippet();
        hard("Attempted to use %s which cannot be invoked until %s is declared",
                sn.name(), unresolved(sn));
    }

    private String unresolved(DeclarationSnippet key) {
        List<String> unresolved = state.unresolvedDependencies(key).collect(Collectors.toList());
        return String.join(", ", unresolved);
    }

    // --- Command processing ---

    private void processCommand(String cmd) {
        // A command boundary finalises any deferred sealed-type hierarchy.
        flushPendingSealed();
        if (cmd.startsWith("/-")) {
            try {
                cmdUseHistoryEntry(Integer.parseInt(cmd.substring(1)));
                return;
            } catch (NumberFormatException ex) {
                // ignore
            }
        }
        String arg = "";
        int sp = cmd.indexOf(' ');
        if (sp >= 0) {
            arg = cmd.substring(sp + 1).trim();
            cmd = cmd.substring(0, sp);
        }
        Command command = commands.get(cmd);
        if (command == null) {
            // Try prefix matching
            Command[] matches = findCommand(cmd, c -> c.kind.isRealCommand);
            if (matches.length == 1) {
                command = matches[0];
            } else if (matches.length == 0) {
                error("No such command: %s", cmd);
                return;
            } else {
                error("Command '%s' is ambiguous: %s", cmd,
                        Arrays.stream(matches).map(c -> c.command).collect(Collectors.joining(", ")));
                return;
            }
        }
        boolean handled = command.run.handle(arg);
        if (handled && command.kind == CommandKind.REPLAY) {
            String replayEntry = (command.command + " " + arg).trim();
            replayableHistory.add(replayEntry);
        }
    }

    private Command[] findCommand(String cmd, Predicate<Command> filter) {
        return commands.values().stream()
                .filter(c -> c.command.startsWith(cmd))
                .filter(filter)
                .toArray(Command[]::new);
    }

    private static final char[] SPINNER_CHARS = {'|', '/', '-', '\\'};

    private List<Suggestion> completeWithSpinner(Terminal terminal, String text, int cursor, int[] anchor) {
        List<Suggestion> suggestions;
        if (text.trim().startsWith("/")) {
            // Command completion may involve network I/O (e.g. artifact search)
            var future = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> commandCompletionSuggestions(text, cursor, anchor));
            suggestions = waitWithSpinner(terminal, future);
        } else {
            suggestions = analysis.completionSuggestions(text, cursor, anchor);
        }
        return suggestions != null ? suggestions : Collections.emptyList();
    }

    private static final int SPINNER_TIMEOUT_MS = 10_000;

    private <T> T waitWithSpinner(Terminal terminal, java.util.concurrent.Future<T> future) {
        int spinIdx = 0;
        int elapsed = 0;
        try {
            while (!future.isDone()) {
                if (elapsed >= SPINNER_TIMEOUT_MS) {
                    future.cancel(true);
                    terminal.writer().print("  \b\b");
                    terminal.writer().flush();
                    return null;
                }
                terminal.writer().print(" " + SPINNER_CHARS[spinIdx % SPINNER_CHARS.length] + "\b\b");
                terminal.writer().flush();
                spinIdx++;
                Thread.sleep(100);
                elapsed += 100;
            }
            // Clear spinner
            terminal.writer().print("  \b\b");
            terminal.writer().flush();
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            terminal.writer().print("  \b\b");
            terminal.writer().flush();
            return null;
        } catch (java.util.concurrent.ExecutionException e) {
            terminal.writer().print("  \b\b");
            terminal.writer().flush();
            return null;
        }
    }

    public List<Suggestion> commandCompletionSuggestions(String code, int cursor, int[] anchor) {
        String prefix = code.substring(0, cursor);
        int space = prefix.indexOf(' ');
        Stream<Suggestion> result;

        if (space == -1) {
            result = commands.values().stream()
                    .distinct()
                    .filter(cmd -> cmd.kind.shouldSuggestCompletions)
                    .map(cmd -> cmd.command)
                    .filter(key -> key.startsWith(prefix))
                    .map(key -> new ArgSuggestion(key + " "));
            anchor[0] = 0;
        } else {
            String arg = prefix.substring(space + 1);
            String cmd = prefix.substring(0, space);
            Command[] candidates = findCommand(cmd, c -> true);
            if (candidates.length == 1) {
                result = candidates[0].completions.completionSuggestions(arg, cursor - (space + 1), anchor).stream();
                if (anchor[0] >= 0) {
                    anchor[0] += space + 1;
                }
            } else {
                result = Stream.empty();
            }
        }

        return result.sorted(Comparator.comparing(Suggestion::continuation))
                .collect(Collectors.toList());
    }

    // --- Built-in commands ---

    private void registerBuiltinCommands() {
        registerCommand(new Command("/list", "[all|start|<id>]",
                "list the source you have typed",
                "Show the source of snippets, prefaced with the snippet id.",
                arg -> { cmdList(arg); return true; },
                CommandKind.NORMAL));
        registerCommand(new Command("/drop", "<name or id>",
                "delete a source entry referenced by name or id",
                "Drop a snippet -- making it inactive.",
                arg -> { cmdDrop(arg); return true; },
                CommandKind.NORMAL));
        registerCommand(new Command("/save", "[all|history|start] <file>",
                "save snippet source to a file",
                "Save the specified snippets and/or commands to the specified file.",
                arg -> { cmdSave(arg); return true; },
                CommandKind.NORMAL));
        registerCommand(new Command("/open", "<file>",
                "open a file as source input",
                "Open a file and read it as input to the tool.",
                arg -> { cmdOpen(arg); return true; },
                CommandKind.NORMAL));
        registerCommand(new Command("/vars", "",
                "list the declared variables and their values",
                "List the current active jshell variables.",
                arg -> { cmdVars(); return true; },
                CommandKind.NORMAL));
        registerCommand(new Command("/methods", "",
                "list the declared methods and their signatures",
                "List the current active jshell methods.",
                arg -> { cmdMethods(); return true; },
                CommandKind.NORMAL));
        registerCommand(new Command("/classes", "",
                "list the declared classes",
                "List the current active jshell classes.",
                arg -> { cmdClasses(); return true; },
                CommandKind.NORMAL));
        registerCommand(new Command("/imports", "",
                "list the imported items",
                "List the current active jshell imports.",
                arg -> { cmdImports(); return true; },
                CommandKind.NORMAL));
        registerCommand(new Command("/exit", "",
                "exit jshell",
                "Leave the jshell tool.",
                arg -> { cmdExit(); return true; },
                CommandKind.NORMAL));
        registerCommand(new Command("/reset", "",
                "reset jshell",
                "Reset the jshell tool code and execution state.",
                arg -> { cmdReset(); return true; },
                CommandKind.NORMAL));
        registerCommand(new Command("/reload", "[restore|quiet]",
                "reset and replay relevant history -- current or previous (restore)",
                "Reset the jshell tool code and execution state then replay each " +
                "valid snippet and any /drop commands in the order they were entered.",
                arg -> { cmdReload(arg); return true; },
                CommandKind.NORMAL));
        registerCommand(new Command("/classpath", "<path>",
                "add a path to the classpath",
                "Add a path to the classpath for evaluation.",
                arg -> { cmdClasspath(arg); return true; },
                CommandKind.NORMAL));
        registerCommand(new Command("/help", "[<command>]",
                "get information about jshell",
                "Display information about jshell.",
                arg -> { cmdHelp(arg); return true; },
                CommandKind.NORMAL));
        registerCommand(new Command("/!", "",
                "re-run last snippet",
                "Redo the last snippet.",
                arg -> { cmdUseHistoryEntry(-1); return true; },
                CommandKind.HIDDEN));
    }

    // --- Command implementations ---

    private void cmdList(String arg) {
        String trimmed = (arg == null) ? "" : arg.trim();
        Stream<Snippet> snippets;
        if ("all".equals(trimmed)) {
            snippets = state.snippets();
        } else if ("start".equals(trimmed)) {
            snippets = state.snippets().filter(s -> startupSnippetIds.contains(s.id()));
        } else if (trimmed.isEmpty()) {
            snippets = state.snippets().filter(s -> state.status(s).isActive());
        } else {
            List<Snippet> matched = state.snippets()
                    .filter(sn -> sn.id().equals(trimmed)
                            || (sn instanceof DeclarationSnippet
                                && ((DeclarationSnippet) sn).name().equals(trimmed)))
                    .filter(sn -> state.status(sn).isActive())
                    .collect(Collectors.toList());
            if (matched.isEmpty()) {
                error("No such snippet: %s", trimmed);
                return;
            }
            snippets = matched.stream();
        }
        snippets.forEach(sn -> hard("%4s : %s", sn.id(), sn.source().replace("\n", "\n       ")));
    }

    private void cmdDrop(String arg) {
        if (arg.isEmpty()) {
            error("/drop requires an argument");
            return;
        }
        state.snippets()
                .filter(sn -> sn.id().equals(arg) || (sn instanceof DeclarationSnippet && ((DeclarationSnippet) sn).name().equals(arg)))
                .filter(sn -> state.status(sn).isActive())
                .findFirst()
                .ifPresentOrElse(
                        sn -> state.drop(sn).forEach(this::handleEvent),
                        () -> error("No such snippet: %s", arg)
                );
    }

    private void cmdSave(String arg) {
        String[] parts = arg.split("\\s+", 2);
        String filename;
        Stream<Snippet> snippets;
        if (parts.length == 2 && ("all".equals(parts[0]) || "start".equals(parts[0]) || "history".equals(parts[0]))) {
            filename = parts[1];
            if (filename.isBlank()) {
                error("/save requires a filename");
                return;
            }
            if ("history".equals(parts[0])) {
                try (BufferedWriter writer = Files.newBufferedWriter(toPathResolvingUserHome(filename))) {
                    for (String entry : replayableHistory) {
                        writer.write(entry);
                        writer.newLine();
                    }
                } catch (IOException e) {
                    error("File '%s' could not be written: %s", filename, e.getMessage());
                }
                return;
            }
            if ("all".equals(parts[0])) {
                snippets = state.snippets();
            } else if ("start".equals(parts[0])) {
                snippets = state.snippets().filter(s -> startupSnippetIds.contains(s.id()));
            } else {
                snippets = state.snippets().filter(s -> state.status(s).isActive());
            }
        } else {
            if (arg.isBlank()) {
                error("/save requires a filename");
                return;
            }
            filename = arg;
            snippets = state.snippets().filter(s -> state.status(s).isActive());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(toPathResolvingUserHome(filename))) {
            snippets.forEach(sn -> {
                try {
                    writer.write(sn.source());
                    writer.newLine();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            error("File '%s' could not be written: %s", filename, e.getCause().getMessage());
        } catch (IOException e) {
            error("File '%s' could not be written: %s", filename, e.getMessage());
        }
    }

    private void cmdOpen(String filename) {
        if (filename.isEmpty()) {
            error("/open requires a filename argument");
            return;
        }
        try {
            String content = Files.readString(toPathResolvingUserHome(filename));
            processSource(content);
            flushPendingSealed();
        } catch (IOException e) {
            error("File '%s' not found: %s", filename, e.getMessage());
        }
    }

    private void cmdVars() {
        state.variables()
                .filter(v -> state.status(v).isActive())
                .forEach(v -> hard("%s %s = %s", v.typeName(), v.name(), state.varValue(v)));
    }

    private void cmdMethods() {
        state.methods()
                .filter(m -> state.status(m).isActive())
                .forEach(m -> hard("%s %s", m.name(), m.signature()));
    }

    private void cmdClasses() {
        state.types()
                .filter(t -> state.status(t).isActive())
                .forEach(t -> hard("%s %s", t.subKind().name().replace("_SUBKIND", "").toLowerCase(), t.name()));
    }

    private void cmdImports() {
        state.imports()
                .forEach(i -> hard("import %s%s", i.isStatic() ? "static " : "", i.fullname()));
    }

    private void cmdExit() {
        regenerateOnDeath = false;
        live = false;
        hard("Goodbye");
    }

    private void cmdReset() {
        hard("Resetting state.");
        live = false;
    }

    private void cmdReload(String arg) {
        List<String> tokens = Arrays.asList(arg.trim().split("\\s+"));
        boolean restore = tokens.contains("restore");
        boolean quiet = tokens.contains("quiet");
        List<String> toReplay = restore ? replayableHistoryPrevious : replayableHistory;
        if (toReplay == null) {
            toReplay = Collections.emptyList();
        }
        hard("Resetting state.");
        hadFailure = false;
        resetState(Collections.emptyList());
        for (String source : toReplay) {
            if (!quiet) {
                hard("%s", source);
            }
            processSource(source);
        }
        flushPendingSealed();
    }

    private void cmdClasspath(String arg) {
        if (arg.isEmpty()) {
            error("/classpath requires a path argument");
            return;
        }
        Path p = toPathResolvingUserHome(arg);
        String name = p.getFileName().toString();
        if (name.chars().anyMatch(c -> GLOB_CHARS.indexOf(c) >= 0)) {
            Path dir = p.getParent() != null ? p.getParent() : Path.of(".");
            try {
                PathMatcher matcher = dir.getFileSystem().getPathMatcher("glob:" + name);
                try (Stream<Path> stream = Files.list(dir)) {
                    List<Path> matched = stream.filter(f -> matcher.matches(f.getFileName()))
                            .sorted()
                            .collect(Collectors.toList());
                    if (matched.isEmpty()) {
                        error("No files matched: %s", arg);
                    } else {
                        matched.forEach(resolved -> {
                            state.addToClasspath(resolved.toString());
                            fluff("Path %s added to classpath", resolved);
                        });
                    }
                }
            } catch (IOException | PatternSyntaxException e) {
                error("Cannot expand glob: %s", e.getMessage());
            }
        } else {
            state.addToClasspath(p.toString());
            fluff("Path %s added to classpath", arg);
        }
    }

    private void cmdHelp(String arg) {
        if (arg.isEmpty()) {
            commands.values().stream()
                    .filter(c -> c.kind.showInHelp)
                    .distinct()
                    .forEach(c -> hard("%-20s -- %s", c.command + " " + c.params, c.description));
        } else {
            Command command = commands.get("/" + arg);
            if (command == null) {
                command = commands.get(arg);
            }
            if (command != null) {
                hard("%s", command.help);
            } else {
                error("No help found for: %s", arg);
            }
        }
    }

    private void cmdUseHistoryEntry(int index) {
        if (replayableHistory == null || replayableHistory.isEmpty()) {
            error("No history to replay");
            return;
        }
        int idx = index < 0 ? replayableHistory.size() + index : index;
        if (idx >= 0 && idx < replayableHistory.size()) {
            String source = replayableHistory.get(idx);
            hard("%s", source);
            processSourceCatchingReset(source);
        } else {
            error("History entry not found: %d", index);
        }
    }

    // --- Arg suggestion ---

    public static class ArgSuggestion implements Suggestion {
        private final String continuation;

        public ArgSuggestion(String continuation) {
            this.continuation = continuation;
        }

        @Override
        public String continuation() {
            return continuation;
        }

        @Override
        public boolean matchesType() {
            return false;
        }
    }

    // --- Utility ---

    public static Path toPathResolvingUserHome(String pathString) {
        if (pathString.startsWith("~")) {
            String home = System.getProperty("user.home");
            String remainder = pathString.substring(1);
            // Strip leading separator — accept both '/' (Unix) and '\' (Windows)
            // so that ~/foo works on all platforms regardless of File.separator.
            if (!remainder.isEmpty() && (remainder.charAt(0) == '/' || remainder.charAt(0) == '\\')) {
                remainder = remainder.substring(1);
            }
            if (remainder.isEmpty()) {
                return Paths.get(home);
            } else {
                return Paths.get(home, remainder);
            }
        }
        return Paths.get(pathString);
    }

    private String version() {
        String v = JetShellTool.class.getPackage().getImplementationVersion();
        return v != null ? v : System.getProperty("java.version", "unknown");
    }

    // --- Command args processing ---

    private List<String> processCommandArgs(String[] args) {
        List<String> loadList = new ArrayList<>();
        Iterator<String> ai = Arrays.asList(args).iterator();
        while (ai.hasNext()) {
            String arg = ai.next();
            if (arg.startsWith("-")) {
                switch (arg) {
                    case "-classpath":
                    case "-cp":
                        if (cmdlineClasspath != null) {
                            cmderr.printf("Conflicting -classpath option.%n");
                            return null;
                        }
                        if (ai.hasNext()) {
                            cmdlineClasspath = ai.next();
                        } else {
                            cmderr.printf("Argument to -classpath missing.%n");
                            return null;
                        }
                        break;
                    case "-startup":
                        if (cmdlineStartup != null) {
                            cmderr.printf("Conflicting -startup or -nostartup option.%n");
                            return null;
                        }
                        if (ai.hasNext()) {
                            String filename = ai.next();
                            try {
                                cmdlineStartup = Files.readString(toPathResolvingUserHome(filename));
                            } catch (IOException e) {
                                cmderr.printf("File '%s' for start-up is not accessible: %s%n", filename, e.getMessage());
                                return null;
                            }
                        } else {
                            cmderr.printf("Argument to -startup missing.%n");
                            return null;
                        }
                        break;
                    case "-nostartup":
                        if (cmdlineStartup != null && !cmdlineStartup.isEmpty()) {
                            cmderr.printf("Conflicting -startup option.%n");
                            return null;
                        }
                        cmdlineStartup = "";
                        break;
                    case "-help":
                        printUsage();
                        return EARLY_EXIT;
                    case "-version":
                        cmdout.printf("jetshell %s%n", version());
                        return EARLY_EXIT;
                    default:
                        cmderr.printf("Unknown option: %s%n", arg);
                        printUsage();
                        return null;
                }
            } else {
                loadList.add(arg);
            }
        }
        return loadList;
    }

    private void printUsage() {
        cmdout.printf("Usage:   jetshell <options> <load files>%n");
        cmdout.printf("where possible options include:%n");
        cmdout.printf("  -classpath <path>          Specify where to find user class files%n");
        cmdout.printf("  -cp <path>                 Specify where to find user class files%n");
        cmdout.printf("  -startup <file>            One run replacement for the start-up definitions%n");
        cmdout.printf("  -nostartup                 Do not run the start-up definitions%n");
        cmdout.printf("  -help                      Print a synopsis of standard options%n");
        cmdout.printf("  -version                   Version information%n");
    }
}
