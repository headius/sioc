/*
 * Copyright (c) 2010, 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sioc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.MalformedURLException;
import java.util.*;
import java.io.*;
import java.dyn.*;
import java.lang.reflect.*;
import java.net.URL;
import java.util.ArrayList;
import static java.dyn.MethodType.*;
import static java.dyn.MethodHandles.*;
import static sioc.Workarounds.asInstance;  // 6983726

/*
  To Run:
  $ METHCP="$DAVINCI/patches/netbeans/meth/build/classes"
  $ (cd "$DAVINCI/patches/netbeans/meth"; ant jar)
  $ $JAVA7X_HOME/bin/javac SIOC.java
  $ $JAVA7X_HOME/bin/java -Xbootclasspath/p:$METHCP -XX:+UnlockExperimentalVMOptions -XX:+EnableInvokeDynamic SIOC
  (print "hello")
  (set! n (+ 2 2))
  (list n (+ 2.1 2.3))
 */

/**
 * SIOC = Scheme in One Class,
 * a small demonstration of JSR 292 APIs.
 * This project will stay within one class file.
 * <p>
 * <ul>
 * <li>Day One:  A REPL that can (print "hello").</li>
 * <li>Day Two:  Access to java.util.List and friends.</li>
 * </ul>
 * <p>
 * Class Workaround is a cheat which works around bugs in pre-FCS 292.
 * @see http://cr.openjdk.java.net/~jrose/pres/indy-javadoc-mlvm/
 * @author John Rose
 */
class SIOC {
    private static final boolean DEBUG = true;

    public static void main(String... args) throws Throwable {
        new SIOC().run(args);
    }
    public void run(String... args) throws Throwable {
        setDefault("input", toReader(System.in));
        setDefault("output", toWriter(System.out));
        setDefault("error-output", toWriter(System.err));
        List<String> av = new ArrayList<>(Arrays.asList(args));
        boolean didRun = false, needInit = true;
        while (!av.isEmpty()) {
            String a = av.remove(0);
            set("arguments", av);
            switch (a) {
            case "--no-init":  needInit = false; continue;
            }
            // following options all need initialization
            if (needInit) { doInit(); needInit = false; }
            switch (a) {
            case "-l":
                F_load(av.remove(0));
                didRun = true; continue;
            case "-l{":
                while ((a = av.remove(0)).equals("-}") == false)  F_load(a);
                didRun = true; continue;
            case "-c":
                F_load_from_string(av.remove(0));
                didRun = true; continue;
            case "-i":
                set("arguments", av);
                interactiveREPL(); return;
            }
            if (a.startsWith("-"))  throw toRTE("bad flag: "+a);
            break;
        }
        if (!didRun) {
            if (needInit) { doInit(); needInit = false; }
            set("arguments", av);
            F_load(System.in);
        }
    }

    private SIOC(int kind, Object value) {
        this.kind = kind;
        this.value = value;
    }
    
    private void doInit() throws Throwable {
        F_load("sioc:SIOC.base.scm");
    }

    private static final int
        KIND_SYMBOL     =  1,  // value is String
        KIND_SPECIAL    =  2,  // value is String
        KIND_INTERPR    = 10,  // value is a map of local bindings
        KIND_META_MAP   = 11;  // value is a meta-map tuple

    private final int kind;
    private final Object value;

    public SIOC() {
        this(KIND_INTERPR, new HashMap<String,Object>());
    }

    public String toString() {
        if (value instanceof String)  return (String) value;
        return super.toString();
    }

    public int hashCode() {
        if (value instanceof String)  return ((String)value).hashCode();
        return super.hashCode();  // identity hash code
    }

    public boolean equals(Object x) {
        if (this == x)  return true;
        if (x instanceof SIOC) {
            SIOC that = (SIOC) x;
            if (this.kind != that.kind)  return false;
            if (value instanceof String)  return ((String)value).equals(that.value);
        }
        return false;
    }

    private Map<String,Object> values() {
        if (kind == KIND_INTERPR)
            return (Map<String,Object>) value;
        throw toRTE("not an interpreter");
    }

    public void setDefault(String name, Object x) {
        if (values().containsKey(name))  return;
        values().put(name, x);
    }
    public void set(String name, Object x) {
        setValue(name, x);
    }

    // strictly local get/set:
    private Object getValue(String name) {
        return values().get(name);
    }
    private boolean hasValue(String name) {
        return values().containsKey(name);
    }
    private void setValue(String name, Object x) {
        if (x == K_HBunbound) {
            values().remove(name);
            return;
        }
        values().put(name, x);
    }

    private static final Object
        K_HBend_of_file = special("#!end-of-file"),
        K_HBend_of_list = special("#!end-of-list"),
        K_HBdefault_object = special("#!default-object"),
        K_HBunbound = special("#!unbound"),
        K_HBimports = special("#!imports"),
        DOT_TOKEN = special("."),
        EMPTY_LIST = Collections.unmodifiableList(Arrays.asList()),
        S_begin = SF_string_Gsymbol("begin"),
        S_quote = SF_string_Gsymbol("quote"),
        S_setB = SF_string_Gsymbol("set!"),
        S_define = SF_string_Gsymbol("define"),
        S_quasiquote = SF_string_Gsymbol("quasiquote"),
        S_unquote = SF_string_Gsymbol("unquote"),
        S_unquote_splicing = SF_string_Gsymbol("unquote-splicing"),
        K_null = null;         // eval(null) => #!null
    private static Object special(String s) {
        return new SIOC(KIND_SPECIAL, s);
    }
    void interactiveREPL() throws Throwable {
        // Set up default bindings:
        setDefault("banner", ";; SIOC");
        setDefault("trailer", ";; exit\n");
        setDefault("prompt", "\n> ");
        Object quit0 = getValue("quit");  // must restore
        Error quitter = new Error("quit"); // must be new
        try {
            setValue("quit", throwException(void.class, Error.class).bindTo(quitter));
            F_display(get("banner"), get("error-output"));
            for (;;) {
                F_display(get("prompt"), get("error-output"));
                Object x;
                try {
                    x = F_read();
                    if (x == K_HBend_of_file)
                        break;
                } catch (Throwable ex) {
                    if (ex instanceof Error)  throw ex;
                    ex.printStackTrace();
                    continue;
                }
                Object y;
                try {
                    y = F_eval(x);
                } catch (Throwable ex) {
                    if (ex instanceof Error)  throw ex;
                    ex.printStackTrace();
                    y = ex;
                }
                F_print(y);
            }
            F_display(get("trailer"), get("error-output"));
        } catch (Error ex) {
            if (ex == quitter)  return;
            throw ex;
        } finally {
            setValue("quit", quit0);
        }
    }

    private Object F_read() throws Throwable { return F_read(get("input")); }
    private Object F_read(Object port) throws Throwable {
        int[] nextc = {NONE};
        Object x = parse(toReader(port), nextc);
        if (nextc[0] != NONE)  throw toRTE("bad syntax: "+(char)nextc[0]);
        return x;
    }

    private void F_display(Object x) throws Throwable { F_display(x, get("output")); }
    private void F_display(Object x, Object port) throws Throwable {
        unparse(x, toWriter(port), false);
    }
    private static String SF_display_to_string(Object x) {
        StringWriter port = new StringWriter();
        try {
            unparse(x, port, false);
        } catch (IOException ex) {
            return "## "+ex;
        }
        return port.toString();
    }

    private void F_print(Object x) throws IOException { F_print(x, get("output")); }
    private void F_print(Object x, Object port) throws IOException {
        unparse(x, toWriter(port), true);
    }
    private static String SF_print_to_string(Object x) {
        StringWriter port = new StringWriter();
        try {
            unparse(x, port, true);
        } catch (IOException ex) {
            return "## "+ex;
        }
        return port.toString();
    }

    private void F_newline() throws Throwable { F_newline(get("output")); }
    private void F_newline(Object port) throws Throwable {
        Writer out = toWriter(port);
        out.write("\n");
        out.flush();
    }

    private List<String> imports(boolean makeIfNone) {
        List<String> imports = (List<String>) getValue(K_HBimports.toString());
        if (imports != null || !makeIfNone)  return imports;
        setValue(K_HBimports.toString(), imports = new ArrayList<>());
        return imports;
    }
    private void F_import(Object name) {
        String fullName = null, baseName = null;
        if (name instanceof List) {
            List<Object> names = (List<Object>) name;
            if (names.size() >= 1)
                fullName = stringOrSymbol(names.get(0));
            if (names.size() >= 2)
                baseName = stringOrSymbol(names.get(1));
            if (names.size() >= 3)
                fullName = null;
        } else {
            fullName = stringOrSymbol(name);
        }
        if (fullName != null && baseName == null && fullName.endsWith("...")) {
            baseName = "...";
            int dot = fullName.length() - 3;
            fullName = fullName.substring(0, dot);
            if (fullName.endsWith("."))  fullName = null;  // too many dots
        }
        if (fullName != null && baseName == null) {
            // java.lang.String => String
            // java.lang.String#concat => concat
            // String#concat => concat
            int dot = fullName.lastIndexOf('.');
            int hash = fullName.indexOf('#', dot+1);
            if (hash > 0)
                baseName = fullName.substring(hash+1);
            else if (dot > 0)
                baseName = fullName.substring(dot+1);
        }
        if (fullName == null || baseName == null)
            throw toRTE("bad import: "+SF_display_to_string(name));
        if (DEBUG)  System.err.println("import "+fullName+" as "+baseName);
        if (baseName.equals("...")) {
            if (!fullName.endsWith(".") && !fullName.endsWith("#"))
                fullName = fullName + ".";
            String scopeName = fullName.substring(0, fullName.length()-1);
            if (lookupScope(scopeName) == null)
                throw toRTE("unbound scope name: "+scopeName);
            List<String> imports = imports(true);
            if (!imports.contains(fullName))
                imports.add(fullName);
        } else {
            // single-name import
            if (!hasValue(baseName)) {
                Object x = get(fullName);
                if (x == K_HBunbound)  throw toRTE("unbound: "+fullName);
                setValue(baseName, x);
            }
        }
    }
    private void F_import(Object... names) throws Throwable {
        //if (names.length == 0) { F_print_imports(); return; }
        // FIXME: make it work with String... (JSR 292 bug?)
        for (Object name : names) {
            F_import(name);
        }
    }
    private Object lookupImported(String name, boolean scopesOnly) { // String
        List<String> imports = imports(false);
        if (imports == null)  return null;
        Object x1 = null;
        for (String i : imports) {
            String name2 = i + name;
            Object x2;
            if (i.endsWith("."))       x2 = lookupQualified(name2);
            else if (i.endsWith("#"))  x2 = scopesOnly ? null : lookupMember(name2);
            else  throw toRTE("bad import: "+i);
            if (x2 == null)  continue;
            if (x1 == null) { x1 = x2; continue; }
            if (x1 != x2)  throw toRTE("ambiguous import of "+name+": "+x1+" & "+x2);
        }
        return x1;
    }
    
    private void F_load(File file) throws Throwable {
        F_load(new FileReader(file));
    }
    private void F_load(URL url) throws Throwable {
        F_load(url.openStream());
    }
    private void F_load(InputStream port) throws Throwable {
        F_load(new InputStreamReader(port));
    }
    private void F_load(Reader port) throws Throwable {
        int[] nextc = {NONE};
        if (!(port instanceof BufferedReader))
            port = new BufferedReader(port);
        try {
            for (;;) {
                Object x = parse(port, nextc);
                if (x == K_HBend_of_file)  break;
                F_eval(x);
            }
        } finally {
            port.close();
        }
    }
    private void F_load(Object source) throws Throwable {
        if (source instanceof String)
            source = toFileOrURL((String) source);
        if (source instanceof File)
            F_load((File)source);
        else if (source instanceof URL)
            F_load((URL)source);
        else
            F_load(toReader(source));
    }
    private void F_load_from_string(String exp) throws Throwable {
        F_load(new StringReader(exp));
    }
    private Object toFileOrURL(String x) {
        if (x.startsWith("file:")) {
            return new File(x.substring(5));
        }
        if (x.startsWith("sioc:")) {
            String rn = x.substring(5);
            InputStream port = SIOC.class.getResourceAsStream(rn);
            if (port == null)  throw toRTE("no such resource: "+rn);
            return port;
        }
        int col = x.indexOf(':');
        if (col > 0 && Character.isLetter(x.charAt(0))) {
            for (int i = 1; i < col; i++) {
                char xc = x.charAt(i);
                if (!Character.isLetterOrDigit(xc) && -1 == "-+.".indexOf(xc))
                    return new File(x);
            }
            try {
                return new URL(x);
            } catch (MalformedURLException ex) {
            }
        }
        return new File(x);
    }

    private Object F_eval(Object exp) throws Throwable {
        if (DEBUG)  System.err.println("eval "+SF_print_to_string(exp));
        return eval(exp);
    }
    private Object eval(Object exp) throws Throwable {
        if (exp instanceof List) {
            List<Object> forms = (List<Object>) exp;
            if (forms.isEmpty())  return exp;  // () self-evaluates
            Object head = forms.get(0);
            if (SF_symbolQ(head)) {
                Object sym = head;
                head = get(SF_symbol_Gstring(sym));
                if (!SF_procedureQ(head)) {
                    // special cases
                    if (S_quote.equals(sym) && forms.size() == 2) {
                        return forms.get(1);
                    }
                    if ((S_setB.equals(sym) || S_define.equals(sym))
                            && forms.size() == 3 && SF_symbolQ(forms.get(1))) {
                        Object var = forms.get(1);
                        Object val = eval(forms.get(2));
                        set(SF_symbol_Gstring(var), val);
                        return null;
                    }
                    // no regular binding for the head symbol; compile it
                    return eval(F_compile(exp));
                }
            } else {
                head = eval(head);
            }
            Object[] args = forms.subList(1, forms.size()).toArray();
            for (int i = 0; i < args.length; i++) {
                args[i] = eval(args[i]);
            }
            return toMethodHandle(head).invokeWithArguments(args);
        } else if (SF_symbolQ(exp)) {
            Object x = get(SF_symbol_Gstring(exp));
            if (x == K_HBunbound)  throw toRTE("unbound: "+SF_print_to_string(exp));
            return x;
        } else {
            return exp;  // self-evaluating
        }
    }

    /**
     * Look up a name in this SIOC.
     * Prefers locally defined names, but also respects imports.
     * Return null if the name is unbound.
     */
    public Object get(String name) {
        Object x;
        x = getValue(name);
        if (x != null) {
            return x;
        }
        if (values().containsKey(name))
            return null;
        int dot = name.indexOf(".");
        if (dot == 0) {
            x = lookupSelector(name);
            if (x != null)
                return x;
        }
        if (name.indexOf("#") >= 0) {
            x = lookupMember(name);
            if (x != null)
                return x;
        }
        String mang = mangle(name);
        if (mang != null) {
            x = metaMapConstant(SIOC_MAP, MANGLE_CONSTANT_PREFIX+mang);
            if (x != null)          // e.g., K_HBdefault
                return x;
            x = metaMapFunctions(SIOC_MAP, MANGLE_GLOBAL_FUNCTION_PREFIX+mang);
            if (x != null) {        // e.g., SF_list
                x = overload(x);
                metaMapConstants(SIOC_MAP).put(name, x);
                return x;
            }
            x = metaMapFunctions(SIOC_MAP, MANGLE_ENGINE_FUNCTION_PREFIX+mang);
            if (x != null) {        // e.g., F_display
                x = overload(bindAllTo(x, this));
                setValue(name, x);  // cache bound version
                return x;
            }
        }
        if (dot > 0) {
            x = lookupQualified(name);
            if (x != null) {
                setValue(name, x);
                return x;
            }
        }
        x = lookupImported(name, false);
        if (x != null) {
            setValue(name, x);
            return x;
        }
        return K_HBunbound;
    }

    private Object F_compile(Object exp) throws Throwable {
        throw toRTE("cannot compile: "+limit(SF_print_to_string(exp)));
    }
    private String limit(String x) {
        if (x.length() > 100)
            return x.substring(0, 80).concat(" ...");
        return x;
    }

    // parsing

    private static final int EOF = -1, NONE = -2;

    private static Object parse(Reader port, int[] nextc) throws IOException {
        int c = nextc[0];
        Object x;
        nextc[0] = NONE;
    restart:
        for (;;) {
            switch (c) {
            case NONE: c = port.read(); continue restart;
            case '(':
                for (List<Object> xs = new ArrayList<>();;) {
                    x = parse(port, nextc);
                    if (x == DOT_TOKEN) {
                        x = parse(port, nextc);
                        if (x instanceof List) {
                            xs.addAll((List<Object>)x);
                            continue;
                        }
                        xs.add(DOT_TOKEN);
                    }
                    if (x == K_HBend_of_list) {
                        if (xs.isEmpty())  return EMPTY_LIST;
                        xs = Arrays.asList(xs.toArray());
                        xs = Collections.unmodifiableList(xs);
                        return xs;
                    }
                    xs.add(x);
                }
            case ')': return K_HBend_of_list;
            case EOF: return K_HBend_of_file;
            case ';':
                for (;;) {
                    c = port.read();
                    switch (c) {
                    case EOF: case '\n': case '\r':
                        continue restart;
                    }
                }
            case '"': return parseQuoted(port, '"');
            case '|': return SF_string_Gsymbol(parseQuoted(port, '|'));
            case '\'': return SF_list(S_quote, parse(port, nextc));
            case '`': return SF_list(S_quasiquote, parse(port, nextc));
            case ',':
                c = port.read();
                if (c == '@')
                    return SF_list(S_unquote_splicing, parse(port, nextc));
                nextc[0] = c;
                return SF_list(S_unquote, parse(port, nextc));
            case '#':
                c = port.read();
                switch (c) {
                case '(':
                    nextc[0] = '(';
                    return ((List<Object>)parse(port, nextc)).toArray();
                case ';':
                    x = parse(port, nextc);
                    if (x == K_HBend_of_file)  return x;
                    c = nextc[0]; nextc[0] = NONE;
                    continue restart;
                case '\\':
                    c = port.read();
                    if (c != EOF) {
                        char val = (char) c;
                        c = port.read();
                        if (isTokenBreak(c)) {
                            nextc[0] = c;
                            return val;
                        }
                        return parseCharName(port, nextc,
                                             new StringBuilder().append(val).append((char)c));
                    }
                    c = '\\';
                    break;
                default:
                    if (isTokenBreak(c))  break;
                    return parseIdent(port, nextc, new StringBuilder("#").append((char)c));
                }
                throw toRTE("bad syntax: #"+(char)c);
            default:
                if (isWhitespace(c)) {
                    do {
                        c = port.read();
                    } while (isWhitespace(c));
                    continue restart;
                }
                return parseIdent(port, nextc, new StringBuilder().append((char)c));
            }
            //throw toRTE("bad syntax: "+(char)c);
        }
    }

    private static Object parseIdent(Reader port, int[] nextc, StringBuilder cs) throws IOException {
        boolean sawEsc = false;
        int pushback = NONE;
    scanIdent:
        for (;;) {
            int c = port.read();
            if (isTokenBreak(c)) {
                pushback = c;
                break scanIdent;
            }
            if (c == '\\') {
                sawEsc = true;
                c = port.read();
                if (c == EOF)
                    break scanIdent;
            }
            cs.append((char)c);
        }
        if (pushback >= 0 && !isWhitespace(pushback))
            nextc[0] = pushback;
        String s = cs.toString();
        if (!sawEsc) {
            Object x = specialIdent(s);
            if (x != null)  return x;
        }
        return SF_string_Gsymbol(s);
    }

    private static char parseCharName(Reader port, int[] nextc, StringBuilder cs) throws IOException {
        int pushback = NONE;
    scanIdent:
        for (;;) {
            int c = port.read();
            // handle pushback
            if (isTokenBreak(c)) {
                pushback = c;
                break scanIdent;
            }
            cs.append((char)c);
        }
        if (pushback >= 0 && !isWhitespace(pushback))
            nextc[0] = pushback;
        String s = cs.toString();
        if (s.length() == 1)  return s.charAt(0);
        if (s.charAt(0) == 'x' || s.charAt(0) == 'X') {
            try {
                int code = Integer.parseInt(s.substring(1), 16);
                if (code == (char)code)
                    return (char) code;
            } catch (NumberFormatException ex) {
                // ignore
            }
        }
        int pos = NAMED_CHARS.indexOf(s);
        if (pos < 0) {
            s = s.toLowerCase();
            pos = NAMED_CHARS.indexOf(s);
        }
        if (s.contains(";"))  pos = -1;
        if (pos > 0) {
            char val = NAMED_CHARS.charAt(pos-1);
            if (!(val >= 'a' && val <= 'z') && val != ';')
                return val;
        }
        throw toRTE("bad syntax: #\\"+cs);
    }

    private static final String NAMED_CHARS =
        "\0nul;\7alarm;\bbackspace;\ttab;\nlinefeed;\nnewline;\13vtab;\14page;\rreturn;\23esc;\40space;\177delete;";

    private static void unparse(Object x, Writer port, boolean isPrint) throws IOException {
        boolean didit = false;
        if (x == null) {
            port.write("#!null");
            didit = true;
        } else if (x instanceof List) {
            List<?> xs = (List<?>)x;
            port.write('(');
            boolean first = true;
            for (Object x1 : xs) {
                if (first) { first = false; } else { port.write(' '); }
                unparse(x1, port, isPrint);
            }
            port.write(')');
            didit = true;
        } else if (x instanceof Object[]) {
            port.write('#');
            unparse(Arrays.asList((Object[])x), port, isPrint);
            didit = true;
        } else if (isPrint && x instanceof Boolean) {
            port.write((Boolean)x ? "#t" : "#f");
            didit = true;
        } else if (isPrint && SF_symbolQ(x)) {
            String s = SF_symbol_Gstring(x);
            if (specialIdent(s) != null || hasSpecialChar(s))
                unparseQuoted(s, port, '|');
            else
                port.write(s);
            didit = true;
        } else if (isPrint && x instanceof String) {
            unparseQuoted((String)x, port, '"');
            didit = true;
        } else if (x instanceof Character) {
            char c = (Character) x;
            if (isPrint) {
                port.write("#\\");
            }
            if (!isPrint || (c > ' ' && c < 0x7F)) {
                port.write(c);
            } else {
                int pos = NAMED_CHARS.indexOf(c);
                if (pos >= 0) {
                    port.write(NAMED_CHARS.substring(pos+1, NAMED_CHARS.indexOf(';', pos+2)));
                } else {
                    port.write('x');
                    port.write(Integer.toHexString(c));
                }
            }
            didit = true;
        }
        if (!didit)
            port.write(String.valueOf(x));
        port.flush();
    }

    private static final String STR_TRANS = "a\7b\bt\tn\nv\13f\fr\r";
    private static String parseQuoted(Reader port, char qc) throws IOException {
        StringBuilder cs = new StringBuilder();
        for (;;) {
            int c = port.read();
            if (c == qc)
                return cs.toString();
            if (c == EOF)
                throw toRTE("EOF in "+qc+"...");
            if (c == '\\') {
                c = port.read();
                int cindex;
                if (c >= 'a' && c <= 'z'
                    && (cindex = STR_TRANS.indexOf((char)c)) >= 0)
                    c = STR_TRANS.charAt(cindex+1);
            }
            cs.append((char)c);
        }
    }
    private static void unparseQuoted(String s, Writer port, char qc) throws IOException {
        port.write(qc);
        for (int len = s.length(), i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == qc || c == '\\') {
                port.write('\\'); port.write(c);
                continue;
            }
            int cindex;
            if (!(c >= 'a' && c <= 'z')
                && (cindex = STR_TRANS.indexOf((char)c)) >= 0) {
                port.write('\\');
                c = STR_TRANS.charAt(cindex-1);
            }
            port.write(c);
        }
        port.write(qc);
    }

    private static final String IDENT_SPECIAL = "(),;'#|\\\"";
    private static boolean hasSpecialChar(String s) {
        for (int len = s.length(), i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (IDENT_SPECIAL.indexOf(c) >= 0)  return true;
            if (isWhitespace(c))  return true;
        }
        return false;
    }

    private static boolean isWhitespace(int c) {
        return Character.isWhitespace(c);
    }
    private static boolean isTokenBreak(int c) {
        switch (c) {
        case EOF: case '"': case '(': case ')':
            return true;
        }
        return isWhitespace(c);
    }

    private static final String NUM_CHARS = "-.0123456789";
    private static Object specialIdent(String s) {
        switch (s) {
        case ".": return DOT_TOKEN;
        case "": return K_HBend_of_file;
        }
        if (s.charAt(0) == '#' && s.length() > 1) {
            switch (s.charAt(1)) {
            case 't': case 'T': return Boolean.TRUE;
            case 'f': case 'F': return Boolean.FALSE;
            }
        }
        if (NUM_CHARS.indexOf(s.charAt(0)) >= 0) {
            String num = s;
            // FIXME: deal with #i, #e, #o, #x, #d, #b, etc.
            try {
                long val = Long.parseLong(num);
                if (val == (int)val)
                    return (int)val;
                return val;
            } catch (NumberFormatException ex) {
                // ignore
            }
            try {
                double val = Double.parseDouble(num);
                return val;
            } catch (NumberFormatException ex) {
                // ignore
            }
        }
        return null;
    }

    // Metaobject protocol

    // Object[] fields for a 'meta map'.
    // We don't have an nested class MetaMap because this is SIOC.
    private static final int // layout of meta-map
        META_SCOPE     = 0,  // the class itself
        META_SUPERS    = 1,  // meta-maps of super scopes
        META_FIELDS    = 2,  // Class.getFields
        META_METHODS   = 3,  // Class.getMethods
        META_CTORS     = 4,  // Class.getConstructors
        META_FUNCTIONS = 5,  // table of {name: mh*}, overloadable
        META_CONSTANTS = 6,  // table of {name: x}, not inherited
        META_COUNT     = 7;  // length of map

    private static Object[] metaMapOf(Class<?> scope) {
        return CV_makeMetaMap.get(scope);
    }
    private static Object[] makeMetaMap(Class<?> scope) {
        boolean publicOnly = getPublicOnly(scope);
        Object[] map = new Object[META_COUNT];
        map[META_SCOPE] = scope;
        map[META_SUPERS] = publicOnly ? initSuperScopes(scope) : new ArrayList<Class<?>>(0);
        map[META_FIELDS] = publicOnly ? scope.getFields() : scope.getDeclaredFields();
        map[META_METHODS] = publicOnly ? scope.getMethods() : scope.getDeclaredMethods();
        map[META_CTORS] = publicOnly ? scope.getConstructors() : scope.getDeclaredConstructors();
        map[META_FUNCTIONS] = new HashMap<>();
        map[META_CONSTANTS] = new HashMap<>();
        return map;
    }
    private static Class<?> metaMapScope(Object[] map) {
        return (Class<?>) map[META_SCOPE];
    }
    private static List<Class<?>> metaMapSupers(Object[] map) {
        return (List<Class<?>>) map[META_SUPERS];
    }
    private static Field[] metaMapFields(Object[] map) {
        return (Field[]) map[META_FIELDS];
    }
    private static Method[] metaMapMethods(Object[] map) {
        return (Method[]) map[META_METHODS];
    }
    private static Constructor[] metaMapConstructors(Object[] map) {
        return (Constructor[]) map[META_CTORS];
    }
    private static Object metaMapFunctions(Object[] map, String name) {
        Map<String, Object> cache = (Map<String,Object>) map[META_FUNCTIONS];
        Object x = cache.get(name);
        if (x == null)  cache.put(name, x = computeMetaMapFunctions(map, name));
        if (x == NO_METHOD_HANDLES_ARRAY)  return null;
        return x;
    }
    private static Map<String, Object> metaMapConstants(Object[] map) {
         return (Map<String,Object>) map[META_CONSTANTS];
    }
    private static Object metaMapConstant(Object[] map, String name) {
        Map<String, Object> cache = (Map<String,Object>) map[META_CONSTANTS];
        Object x = cache.get(name);
        if (x == null)  cache.put(name, x = computeMetaMapConstant(map, name));
        return x;
    }
    private static final String
        FIELD_GETTER_PREFIX = "get:",
        FIELD_SETTER_PREFIX = "set:",
        METHOD_CALLER_PREFIX = "call:",
        NEW_INSTANCE_NAME = "new";
    private static final Pattern ARITY_PATTERN = Pattern.compile(":[0-9]+$");
    private static Object computeMetaMapFunctions(Object[] map, String name) {
        // parse the name
        int minArity = 0, maxArity = (char)-1;
        int col = name.indexOf(':');
        if (col >= 0) {
            Matcher match = ARITY_PATTERN.matcher(name);
            if (match.find()) {
                int apos = match.start();
                String arity = name.substring(apos+1);
                minArity = maxArity = Integer.parseInt(arity);
                name = name.substring(0, apos);
                col = name.indexOf(':');
            }
        }
        String fieldName = name, methodName = name;
        boolean isSetter = false, isConstructor = false;
        if (col >= 0) {
            String baseName = name.substring(col+1);
            if (name.startsWith(FIELD_GETTER_PREFIX)) {
                fieldName = baseName;
                methodName = null;
            } else if (name.startsWith(FIELD_SETTER_PREFIX)) {
                fieldName = baseName;
                methodName = null;
                isSetter = true;
            } else if (name.startsWith(METHOD_CALLER_PREFIX)) {
                fieldName = null;
                methodName = baseName;
            }
        } else if (name.equals(NEW_INSTANCE_NAME)) {
            fieldName = methodName = null;
            isConstructor = true;
        }
        Class<?> selfType = metaMapScope(map);
        Iterator<Class<?>> supers = null;
        ArrayList<MethodHandle> mhs = new ArrayList<>();
        for (;;) {
            try {
                if (fieldName != null) {
                    for (Field f : metaMapFields(map)) {
                        if (f.getName().equals(fieldName)) {
                            if (isSetter)
                                maybeAdd(mhs, LOOKUP.unreflectSetter(f));
                            else
                                maybeAdd(mhs, LOOKUP.unreflectGetter(f));
                            break;
                        }
                    }
                }
                Member badva = null;
                if (isConstructor) {
                    for (Constructor c : metaMapConstructors(map)) {
                        MethodHandle mh = LOOKUP.unreflectConstructor(c);
                        boolean isva = isVarArgs(mh.type(), VARARGS_TYPE);
                        if (isva != c.isVarArgs()) { badva = c; continue; }
                        maybeAdd(mhs, mh);
                    }
                }
                if (methodName != null) {
                    for (Method m : metaMapMethods(map)) {
                        if (!m.getName().equals(methodName))  continue;
                        MethodHandle mh = LOOKUP.unreflect(m);
                        MethodType type = mh.type();
                        int arity = type.parameterCount();
                        if (arity < minArity || arity > maxArity)  continue;
                        System.err.println("found "+mh);//@@
                        boolean isva = isVarArgs(type, VARARGS_TYPE);
                        if (isva != m.isVarArgs()) { badva = m; continue; }
                        if (!Modifier.isStatic(m.getModifiers()))
                            mh = mh.asType(mh.type().changeParameterType(0, selfType));
                        maybeAdd(mhs, mh);
                    }
                }
                if (badva != null && mhs.isEmpty())
                    throw toRTE("bad varargs: "+badva);
            } catch (ReflectiveOperationException ex) {
                if (DEBUG)  System.err.println(ex);
            }
            if (supers == null)
                supers = metaMapSupers(map).iterator();
            if (supers.hasNext())
                map = metaMapOf(supers.next());
            else
                break;
        }
        int mhCount = mhs.size();
        switch (mhCount) {
        case 0:  return NO_METHOD_HANDLES_ARRAY;
        case 1:  return mhs.get(0);
        default: return mhs.toArray(new MethodHandle[mhCount]);
        }
    }
    private static void maybeAdd(List<MethodHandle> mhs, MethodHandle mh) {
        if (mh == null)  return;
        MethodType type = mh.type();
        for (MethodHandle mh0 : mhs) {
            if (mh0.type().equals(type))  return;
        }
        mhs.add(mh);
    }
    private static Object computeMetaMapConstant(Object[] map, String name) {
        for (Field f : metaMapFields(map)) {
            if (!f.getName().equals(name))  continue;
            if (!Modifier.isStatic(f.getModifiers()))  continue;
            if (!Modifier.isFinal(f.getModifiers()))  continue;
            try {
                return f.get(null);
            } catch (final IllegalArgumentException | IllegalAccessException ex) {
                return ex;
            }
        }
        return null;
    }

    private static List<Class<?>> initSuperScopes(Class<?> scope) {
        List<Class<?>> supers = new ArrayList<>();
        if (scope.isArray()) {
            Class<?> elt = scope.getComponentType();
            if (elt == Object.class || elt.isPrimitive()) {
                supers.add(java.lang.reflect.Array.class);
                supers.add(java.util.Arrays.class);
            } else {
                supers.add(Array.newInstance(elt.getSuperclass(), 0).getClass());
            }
        } else if (!scope.isInterface()) {
            Class<?> supc = scope.getSuperclass();
            if (supc != null)  supers.add(supc);
        }
        Collections.addAll(supers, scope.getInterfaces());
        if (scope.isPrimitive()) {
            supers.add(toWrapperType(scope));
        }
        return supers;
    }
    private static boolean getPublicOnly(Class<?> scope) {
        return scope != SIOC.class;  // generalize?
    }

    private static MethodHandle lookupSelector(String name) { // .length
        if (!name.startsWith("."))  return null;
        name = name.substring(1);
        MethodHandle vamh = MH_applySelector.bindTo(name);
        return arityOverload(null, vamh);
    }
    // FIXME: Should have a separate receiver argument, not buried in varargs.
    private static Object applySelector(String name, //Object receiver,
                                        Object... args) throws Throwable {
        //if (DEBUG) System.err.println("applySelector "+name+" to "+Arrays.asList(args));
        Object receiver = args[0];
        Class<? extends Object> rclass = receiver.getClass();
        Object mhs = metaMapFunctions(metaMapOf(rclass), name);
        if (mhs == null)  throw toRTE("selector unbound: "+rclass.getName()+"#"+name);
        MethodHandle mh = findFirstApplicable(mhs, args.length, args);
        return mh.invokeWithArguments(args);
    }
    private static MethodHandle findFirstApplicable(Object mhs, int argc, Object... argv) {
        if (mhs instanceof MethodHandle)
            return (MethodHandle) mhs;
        MethodHandle[] mha = (MethodHandle[]) mhs;
        for (MethodHandle mh : mha) {
            if (isApplicable(mh, argc, argv))
                return mh;
        }
        return mha[0];
    }
    private static boolean isApplicable(MethodHandle mh, int argc, Object... argv) {
        MethodType type = mh.type();
        int pc = type.parameterCount();
        boolean isva = isVarArgs(type, VARARGS_TYPE);
        if (isva)  pc -= 1;
        if (!isva ? argc != pc : argc < pc)  return false;
        if (argv != null) {
            int i = 0;
            for (Class<?> pt : type.parameterList()) {
                if (i == argv.length)  break;
                Object arg = argv[i++];
                if (!canConvertArgumentTo(pt, arg))  return false;
            }
        }
        return true;
    }
    
    private Object lookupMember(String name) { // String#length
        int hash = name.indexOf('#');
        if (hash < 0)  return null;
        Object scope = lookupScope(name.substring(0, hash));
        if (!(scope instanceof Class))  return null;
        name = name.substring(hash+1);
        Object[] map = metaMapOf((Class<?>) scope);
        Object x = metaMapConstant(map, name);
        if (x != null)  return x;
        return overload(metaMapFunctions(map, name));
    }

    private static Object lookupQualified(String name) { // java.lang.String
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException ex) {
        }
        return Package.getPackage(name);
    }
    private static boolean isScope(Object x) {
        return x instanceof Class || x instanceof Package;
    }
    private Object lookupScope(String name) { // String
        Object x = values().get(name);
        if (isScope(x))  return x;
        x = lookupQualified(name);
        if (isScope(x))  return x;
        x = lookupImported(name, true);
        if (isScope(x))  return x;
        return null;
    }

    private static final MethodHandle[] NO_METHOD_HANDLES_ARRAY = {};
    private static final List<MethodHandle> NO_METHOD_HANDLES = Arrays.asList(NO_METHOD_HANDLES_ARRAY);

    private static List<MethodHandle> expandMHList(Object mhs) {
        if (mhs == null)
            return NO_METHOD_HANDLES;
        else if (mhs instanceof MethodHandle)
            return Arrays.asList((MethodHandle) mhs);
        else if (mhs instanceof MethodHandle[])
            return Arrays.asList((MethodHandle[]) mhs);
        else
            return (List<MethodHandle>) mhs;
    }
    private static Object collapseMHList(List<MethodHandle> mhs) {
        if (mhs == null || mhs.isEmpty())
            return null;
        else if (mhs.size() == 1)
            return mhs.get(0);
        else
            return mhs;
    }

    private static final Class<?> VARARGS_TYPE = Object[].class;  // local marker for VA methods

    private static final Lookup LOOKUP = MethodHandles.lookup();
    private static final MethodHandle MH_chooseMethod;
    private static final MethodHandle MH_vaTypeHandler;
    private static final MethodHandle MH_bindTypeHandler;
    private static final MethodHandle MH_applySelector;
    private static final MethodHandle MH_flattenVarargs;
    private static final Comparator<Class<?>> C_compareClasses;
    private static final Comparator<MethodHandle> C_compareMethodHandles;
    private static final ClassValue<Object[]> CV_makeMetaMap;
    private static final Object[] SIOC_MAP;
    static {
        try {
            MH_chooseMethod = LOOKUP
                .findStatic(SIOC.class, "chooseMethod",
                            methodType(MethodHandle.class,
                                       MethodHandle[].class, Object[].class));
            MH_vaTypeHandler = LOOKUP
                .findStatic(SIOC.class, "vaTypeHandler",
                            methodType(MethodHandle.class,
                                       MethodHandle.class, MethodType.class,
                                       Integer.class, MethodHandle[].class,
                                       MethodHandle.class));
            MH_bindTypeHandler = LOOKUP
                .findStatic(SIOC.class, "bindTypeHandler",
                            methodType(MethodHandle.class,
                                       MethodHandle.class, MethodType.class,
                                       MethodHandle.class, Object.class));
            MH_applySelector = LOOKUP
                .findStatic(SIOC.class, "applySelector",
                            methodType(Object.class,
                                       String.class,
                                       Object[].class));
            MH_flattenVarargs = LOOKUP
                .findStatic(SIOC.class, "flattenVarargs",
                            methodType(Object.class,
                                       Class.class,
                                       Object[].class));
            MethodType C_type = methodType(int.class,
                                           Object.class, Object.class);
            MethodType CV_type = methodType(Object.class, Class.class);
            MethodHandle MH_compareClasses = LOOKUP
                .findStatic(SIOC.class, "compareClasses",
                            methodType(int.class,
                                       Class.class, Class.class));
            C_compareClasses = asInstance(MH_compareClasses.asType(C_type),
                                          Comparator.class);
            MethodHandle MH_compareMethodHandles = LOOKUP
                .findStatic(SIOC.class, "compareMethodHandles",
                            methodType(int.class,
                                       MethodHandle.class, MethodHandle.class));
            C_compareMethodHandles = asInstance(MH_compareMethodHandles.asType(C_type),
                                                Comparator.class);
            MethodHandle MH_makeMetaMap = LOOKUP
                .findStatic(SIOC.class, "makeMetaMap",
                            methodType(Object[].class, Class.class));
            ClassValue cv = asInstance(MH_makeMetaMap.asType(CV_type),
                                       ClassValue.class);
            CV_makeMetaMap = (ClassValue<Object[]>) cv;
            SIOC_MAP = metaMapOf(SIOC.class);
        } catch (ReflectiveOperationException ex) {
            throw toIE("bad lookup", ex);
        }
    }

    // mh.bindTo(x) but preserving type handler (variadic properties)
    // question:  what relation does this have to JSR 292?
    private static MethodHandle bindCarefully(MethodHandle mh, Object x) {
        // ?? mh = mh.asType(mh.type().changeParameterType(0, x.getClass()))
        MethodHandle leadmh = mh.bindTo(x);
        MethodHandle typeHandler = insertArguments(MH_bindTypeHandler, 2, mh, x);
        return leadmh.withTypeHandler(typeHandler);
    }
    private static MethodHandle bindTypeHandler(MethodHandle leadmh, MethodType type,
                                                MethodHandle mh, Object x) {
        // mh is the original variadic method
        Class<?> xtype = mh.type().parameterType(0);
        return mh.asType(type.insertParameterTypes(0, xtype)).bindTo(x);
    }

    /**
     * Take the given series of method handles and combine them into one
     * method handle which will dispatch on its arity and argument types
     * to call the first applicable member the given series.
     * @param mhs set of method handles to combine into one
     * @param varargsType the type (if any) to treat as a rest-argument
     * @return a combined method handle
     */
    public static MethodHandle overload(List<MethodHandle> mhs, Class<?> varargsType) {
        if (mhs == null || mhs.isEmpty())  return null;

        // Pass 1: Determine arities and detect varargs.
        int minac = (char)-1, maxac = -1, varac = -1;
        for (MethodHandle mh : mhs) {
            int ac = mh.type().parameterCount();
            if (isVarArgs(mh.type(), varargsType)) {
                varac = --ac;  // skip trailing argument
            }
            if (minac > ac)  minac = ac;
            if (maxac < ac)  maxac = ac;
        }
        if (minac == maxac && varac < 0) {
            // common arity & no varargs => overload by type only
            return typeOverload(mhs);
        }

        // Pass 2: Group MHs by arity.
        MethodHandle[] mhByArity = new MethodHandle[maxac+1];
        List<MethodHandle>[] mhListByArity = null;
        boolean[] hasVarArgs = (varac < 0 ? null : new boolean[maxac+1]);
        for (MethodHandle mh : mhs) {
            int ac = mh.type().parameterCount();
            if (isVarArgs(mh.type(), varargsType))
                hasVarArgs[--ac] = true;
            if (mhByArity[ac] == null) {
                // unique MH of this arity
                mhByArity[ac] = mh;
                continue;
            }
            // multiple MHs of this arity; make some data structure
            if (mhListByArity == null)
                mhListByArity = (List<MethodHandle>[]) new List[maxac+1];
            List<MethodHandle> mhsOfArity = mhListByArity[ac];
            if (mhsOfArity == null) {
                mhsOfArity = new ArrayList<>();
                mhsOfArity.add(mhByArity[ac]);
                mhListByArity[ac] = mhsOfArity;
            } else {
                assert(mhsOfArity.contains(mhByArity[ac]));
            }
            mhsOfArity.add(mh);
        }

        // Pass 3: Group MHs by arity.
        // Merge each arity group into a single mh.
        List<MethodHandle> mhsToOverload = new ArrayList<>(maxac - minac + 1);
        MethodHandle vamh = null;  // longest-arity varargs method
        for (int ac = minac; ac <= maxac; ac++) {
            List<MethodHandle> mhsOfArity = (mhListByArity == null ? null : mhListByArity[ac]);
            boolean hasva = (hasVarArgs == null ? false : hasVarArgs[ac]);
            if (mhsOfArity == null && !hasva && vamh == null) {
                // simple case: one method of this arity, no varargs
                addIfNotNull(mhsToOverload, mhByArity[ac]);
                continue;
            }
            if (mhsOfArity == null) {
                mhsOfArity = new ArrayList<MethodHandle>();
                addIfNotNull(mhsOfArity, mhByArity[ac]);
            } else {
                assert(mhsOfArity.contains(mhByArity[ac]));  // aready on list
            }
            if (hasva) {
                // this arity introduces a varargs method; find it (or them)
                List<MethodHandle> vamhs = new ArrayList<>();
                for (Iterator<MethodHandle> i = mhsOfArity.iterator(); i.hasNext(); ) {
                    MethodHandle mh1 = i.next();
                    if (isVarArgs(mh1.type(), varargsType)) {
                        i.remove();
                        vamhs.add(mh1);
                    }
                }
                assert(vamhs.size() > 0);
                // completely replace any lower-arity varargs method(s):
                vamh = typeOverload(vamhs);
            }
            MethodHandle mhOfArity = null;
            if (!mhsOfArity.isEmpty()) {
                mhOfArity = typeOverload(mhsOfArity);
            } else if (vamh != null) {
                // use va method(s) only if there are no non-va methods
                mhOfArity = adjustArity(vamh, ac);
            }
            addIfNotNull(mhsToOverload, mhOfArity);
        }

        // Merge all the groups together.
        return arityOverload(mhsToOverload, vamh);
    }
    private static MethodHandle overload(Object mhs) {
        return overload(mhs, VARARGS_TYPE);
    }
    private static MethodHandle overload(Object mhs, Class<?> varargsType) {
        if (mhs instanceof MethodHandle) {
            MethodHandle mh = (MethodHandle) mhs;
            if (!isVarArgs(mh.type(), varargsType))
                return mh;
        }
        return overload(expandMHList(mhs), varargsType);
    }
    private static Object bindAllTo(Object mhs, Object arg0) {
        if (mhs instanceof MethodHandle)
            return bindTo((MethodHandle) mhs, arg0);
        List<MethodHandle> bmhs = new ArrayList<>();
        for (MethodHandle mh : expandMHList(mhs)) {
            maybeAdd(bmhs, bindTo(mh, arg0));
        }
        return bmhs;
    }
    private static MethodHandle bindTo(MethodHandle mh, Object arg0) {
        if (mh == null)  return null;
        MethodType type = mh.type();
        int arity = type.parameterCount();
        if (arity == 0)  return null;
        Class<?> t0 = type.parameterType(0);
        if (arity == 1 && t0 == VARARGS_TYPE) {
            // insert an extra parameter so we can bind it
            mh = adjustArity(mh, 1, true);
            type = mh.type();
            t0 = type.parameterType(0);
        }
        if (!canConvertArgumentTo(t0, arg0))  return null;
        return mh.bindTo(arg0);
    }

    private static <T> void addIfNotNull(List<T> ls, T x) {
        if (x != null)  ls.add(x);
    }
    public static boolean isVarArgs(MethodType type, Class<?> varargsType) {
        if (varargsType == null)  return false;
        int ac = type.parameterCount();
        return (ac > 0 && type.parameterType(ac-1) == varargsType);
    }

    public static MethodHandle adjustArity(MethodHandle mh, int ac) {
        return adjustArity(mh, ac, false);
    }
    public static MethodHandle adjustArity(MethodHandle mh, int ac, boolean keepVarargs) {
        MethodType type = mh.type();
        int fixedArgs = type.parameterCount() - 1;
        int collectArgs = ac - fixedArgs;
        if (fixedArgs < 0 || collectArgs < 0)  return null;
        Class<?> vap = type.parameterType(fixedArgs);  // varargs param
        Class<?> ptype = vap.getComponentType();
        if (ptype == null) {
            ptype = Object.class;
            vap = Object[].class;
            type = type.changeParameterType(fixedArgs, vap);
            mh = explicitCastArguments(mh, type);
        }
        if (keepVarargs) {
            if (collectArgs == 0)  return mh;
            // convert (A..., P[]) to (A..., P..., P[])
            MethodHandle flatten = MH_flattenVarargs.bindTo(vap);
            flatten = flatten.asType(methodType(vap, Object[].class));
            MethodHandle vamh = filterArguments(mh, fixedArgs, flatten);
            return vamh.asCollector(Object[].class, collectArgs+1);
        } else {
            // convert (A..., P[]) to (A..., P...)
            return mh.asCollector(vap, collectArgs);
        }
    }
    private static Object flattenVarargs(Class<?> vatype, Object[] argv) {
        System.out.println("flattenVarargs "+Arrays.deepToString(argv));
        Class<? extends Object[]> vaptype = vatype.asSubclass(Object[].class);
        int firstCount = argv.length - 1;
        Object[] last = (Object[]) argv[firstCount];
        if (last == null)
            return Arrays.copyOfRange(argv, 0, firstCount, vaptype);
        int lastCount = last.length;
        Object[] allargv = Arrays.copyOfRange(argv, 0, firstCount + lastCount, vaptype);
        System.arraycopy(last, 0, allargv, firstCount, lastCount);
        System.out.println("flattenVarargs => "+Arrays.deepToString(allargv));
        return allargv;
    }

    public static MethodHandle arityOverload(List<MethodHandle> mhs, MethodHandle vamh) {
        if (mhs == null)  mhs = Collections.emptyList();
        if (vamh == null && mhs.size() <= 1)
            return (mhs.isEmpty() ? null : mhs.get(0));
        //System.err.println("arityOverload"+mhs+", va="+vamh);
        int minac = (char)-1, maxac = -1;
        for (MethodHandle mh : mhs) {
            int ac = mh.type().parameterCount();
            if (minac > ac)  minac = ac;
            if (maxac < ac)  maxac = ac;
        }
        if (vamh != null) {
            int ac = vamh.type().parameterCount();
            ac -= 1;  // ignore trailing param
            if (minac > ac)  minac = ac;
            ac += 8;  // pre-spin several extra arity handlers
            if (maxac < ac)  maxac = ac;
        }
        MethodHandle[] mhv = new MethodHandle[maxac - minac + 1];
        for (MethodHandle mh : mhs) {
            int ac = mh.type().parameterCount();
            assert(mhv[ac - minac] == null); // no duplicate arity
            mhv[ac - minac] = mh;
        }
        int vamin = (char)-1;
        if (vamh != null) {
            vamin = vamh.type().parameterCount() - 1;
            for (int ac = vamin; ac <= maxac; ac++) {
                if (mhv[ac - minac] == null)
                    mhv[ac - minac] = adjustArity(vamh, ac);
            }
        }
        MethodHandle leadmh = mhs.isEmpty() ? adjustArity(vamh, vamin) : mhs.get(0);
        MethodHandle typeHandler = insertArguments(MH_vaTypeHandler, 2, minac, mhv, vamh);
        return leadmh.withTypeHandler(typeHandler);
    }
    private static MethodHandle vaTypeHandler(MethodHandle leadmh, MethodType type,
                                              Integer minac,
                                              MethodHandle[] mhv,
                                              MethodHandle vamh) {
        int ac = type.parameterCount();
        if (ac >= minac && (ac - minac) < mhv.length) {
            MethodHandle mh = mhv[ac - minac];
            if (mh != null)
                return mh.asType(type);
        }
        if (vamh != null) {
            return adjustArity(vamh, ac).asType(type);
        }
        return leadmh;
    }

    public static MethodHandle typeOverload(List<MethodHandle> mhs) {
        if (mhs.size() <= 1)
            return (mhs.isEmpty() ? null : mhs.get(0));
        MethodHandle leadmh = mhs.get(0);
        //System.err.println("typeOverload "+mhs);
        MethodHandle[] mhv = mhs.toArray(new MethodHandle[mhs.size()]);
        // try to move more general types to the back of the list
        Arrays.sort(mhv, C_compareMethodHandles);
        boolean[] argTypesVary = new boolean[mhv[0].type().parameterCount()];
        MethodType jtype = joinAllTypes(mhv, argTypesVary);
        // this path might fail but it produces a better MH:
        MethodHandle result = chooseMethodGuard(mhv, jtype, argTypesVary);
        if (result != null)  return result;
        // slow but sure:
        return chooseMethodGuardSlow(mhv, jtype);
    }

    private static MethodHandle chooseMethod(MethodHandle[] mhs, Object[] args) {
        // Pick first matching method.  Caller pre-ordered them.
    scanList:
        for (MethodHandle mh : mhs) {
            int i = 0;
            for (Class<?> pt : mh.type().parameterList()) {
                if (!canConvertArgumentTo(pt, args[i]))  continue scanList;
            }
            return mh;
        }
        return mhs[mhs.length-1];  // return arbitrary, to signal an error
    }

    private static MethodHandle chooseMethodGuardSlow(MethodHandle[] mhs,
                                                  MethodType jtype) {
        int ac = mhs[0].type().parameterCount();
        MethodHandle chooser = MH_chooseMethod.bindTo(mhs).asCollector(Object[].class, ac);
        return foldArguments(genericInvoker(jtype), chooser);
    }

    private static MethodHandle chooseMethodGuard(MethodHandle[] mhs,
                                                  MethodType jtype,
                                                  boolean[] argTypesVary) {
        // Pick first matching method.  Build up the decision tree backwards.
        int ac = jtype.parameterCount();
        MethodType ttype = jtype.changeReturnType(boolean.class);
        MethodType ttypeBase = ttype.insertParameterTypes(0, Collections.<Class<?>>nCopies(ac, Class.class));
        MethodHandle testBase;
        try {
            testBase = LOOKUP.findStatic(SIOC.class, "canConvertArgumentTo", ttypeBase);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
        final int THRESH = 4;
        if (mhs.length > (THRESH*3)/2) {
            // speed only the first four cases
            MethodHandle[] mhsHead = Arrays.copyOf(mhs, THRESH+1);
            MethodHandle[] mhsTail = Arrays.copyOfRange(mhs, THRESH, mhs.length);
            mhsHead[THRESH] = chooseMethodGuardSlow(mhsTail, jtype);
            mhs = mhsHead;
        }
        MethodHandle tail = mhs[mhs.length-1].asType(jtype);
        for (int i = mhs.length-2; i >= 0; i--) {
            MethodHandle mh = mhs[i];
            MethodHandle test = testBase;
            for (int j = 0; j < ac; j++) {
                Class<?> testClass = null;
                if (argTypesVary[j])
                    testClass = mh.type().parameterType(j);
                test = test.bindTo(testClass);
            }
            tail = guardWithTest(test.asType(ttype),
                                 mh.asType(jtype),
                                 tail.asType(jtype));
        }
        return tail;
    }

    private static boolean canConvertArgumentTo(Class<?> t0, Object a0) {
        if (t0 == null)  return true;
        if (a0 == null)  return !t0.isPrimitive();
        return canConvert(a0.getClass(), t0);
    }
    // binary and ternary versions (for use as curried guards)
    private static boolean canConvertArgumentTo(Class<?> t0, Class<?> t1,
                                                Object a0, Object a1) {
        return (canConvertArgumentTo(t0, a0) &&
                canConvertArgumentTo(t1, a1));
    }
    private static boolean canConvertArgumentTo(Class<?> t0, Class<?> t1, Class<?> t2,
                                                Object a0, Object a1, Object a2) {
        return (canConvertArgumentTo(t0, a0) &&
                canConvertArgumentTo(t1, a1) &&
                canConvertArgumentTo(t2, a2));
    }

    private static MethodType joinAllTypes(MethodHandle[] mhs, boolean[] argTypesVary) {
        Class<?>[] dtypes = mhs[0].type().parameterArray();
        Class<?> rtype = mhs[0].type().returnType();
        boolean rtypeVaries = false;
        for (MethodHandle mh : mhs) {
            MethodType t = mh.type();
            if (rtype != t.returnType()) {
                rtype = joinClass(rtype, t.returnType());
                if (rtype.isPrimitive())
                    rtype = Object.class;  // box the result
            }
            assert(t.parameterCount() == dtypes.length);
            for (int i = 0; i < dtypes.length; i++) {
                Class<?> pt = t.parameterType(i);
                Class<?> dt = dtypes[i];
                if (pt == dt)  continue;
                if (argTypesVary != null)
                    argTypesVary[i] = true;
                dtypes[i] = joinClass(dt, pt);
                if (dtypes[i].isPrimitive())
                    dtypes[i] = Object.class;  // box the argument
            }
        }
        return methodType(rtype, dtypes);
    }

    private static Class<?> joinClass(Class<?> t0, Class<?> t1) {
        if (t0 == t1)  return t0;
        if (canConvert(t0, t1))  return t1;
        if (canConvert(t1, t0))  return t0;
        return Object.class;  // give up
    }

    private static boolean canConvert(Class<?> t0, Class<?> t1) {
        if (t1 == Object.class)  return true;
        if (t1.isPrimitive()) {
            Class<?> pt0 = toPrimitiveType(t0);
            if (pt0.isPrimitive())
                return ((primitiveOrderNumber(pt0) & 0xF)
                        <=
                        (primitiveOrderNumber(t1) & 0xF));
            t1 = toWrapperType(t1);
        }
        if (t0 == Void.class)  return true;
        if (t0.isPrimitive())
            t0 = toWrapperType(t1);
        return t1.isAssignableFrom(t0);
    }

    private static int compareClasses(Class<?> t0, Class<?> t1) {
        if (t0 == null || t1 == null)
            return t0 != null ? -1 : t1 != null ? +1 : 0;
        if (t0.equals(t1))  return 0;
        if (t0.isPrimitive() || t1.isPrimitive()) {
            // all primitives come first
            if (!t1.isPrimitive())  return -1;
            if (!t0.isPrimitive())  return +1;
            return ((primitiveOrderNumber(t0) & 0xF)
                    -
                    (primitiveOrderNumber(t1) & 0xF));
        }
        if (t1.isAssignableFrom(t0))  return -1;
        if (t0.isAssignableFrom(t1))  return +1;
        // arbitrary:
        int cmp = t0.getName().compareTo(t1.getName());
        int res = (cmp << 8);
        if ((res >> 8) != cmp)
            res = ((cmp > 0 ? 1 : -1) << 0);
        return res;
    }

    private static Class<?> toWrapperType(Class<?> type) {
        if (type == null || !type.isPrimitive())  return type;
        return methodType(type).wrap().returnType();
    }
    private static Class<?> toPrimitiveType(Class<?> type) {
        if (type == null || type.isPrimitive())  return type;
        return methodType(type).unwrap().returnType();
    }
    private static int primitiveOrderNumber(Class<?> type) {
        switch (type.getName()) {
        case "boolean":     return 0x10;
        case "byte":        return 0x21;
        case "char":        return 0x12;
        case "short":       return 0x23;
        case "int":         return 0x24;
        case "long":        return 0x65;
        case "float":       return 0x56;
        case "double":      return 0x77;
        case "void":        return 0x08;
        }
        return -1;
    }

    private static int compareMethodHandles(MethodHandle mh0, MethodHandle mh1) {
        return compareMethodTypes(mh0.type(), mh1.type());
    }
    private static int compareMethodTypes(MethodType t0, MethodType t1) {
        if (t0 == null || t1 == null)
            return t0 != null ? -1 : t1 != null ? +1 : 0;
        if (t0.equals(t1))  return 0;
        // arbitrary:
        int ac = t0.parameterCount();
        if (ac != t1.parameterCount())
            return ac - t1.parameterCount();
        for (int i = 0; i < ac; i++) {
            int cmp = compareClasses(t0.parameterType(i), t1.parameterType(i));
            if (cmp != 0)  return cmp;
        }
        return compareClasses(t0.returnType(), t1.returnType());
    }

    private static final String
        MANGLE_ENGINE_FUNCTION_PREFIX = "F_",
        MANGLE_GLOBAL_FUNCTION_PREFIX = "SF_",
        //MANGLE_SYMBOL_PREFIX = "S_",  // not used reflectively
        MANGLE_CONSTANT_PREFIX = "K_";
    private static final String MANGLE_CHARS = "A@B!C:D/E=G>H#L<M&P+Q?S*T%V^";
    //  also: "_-X0Ua": X hex, U upper
    private static String mangle(String s) {
        StringBuilder mang = new StringBuilder(8 + s.length());
        for (int len = s.length(), i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == '-') {
                mang.append('_');
                continue;
            }
            boolean upper = (c >= 'A' && c <= 'Z');
            if (upper) {
                mang.append('U').append(Character.toLowerCase(c));
                continue;
            }
            int cindex = MANGLE_CHARS.indexOf(c);
            if (cindex >= 0) {
                mang.append(MANGLE_CHARS.charAt(cindex-1));
                continue;
            }
            if (Character.isJavaIdentifierPart(c)) {
                mang.append(c);
                continue;
            }
            String xstr = Integer.toHexString(c);
            mang.append('X');
            switch (xstr.length()) {
            case 1: mang.append('0');
            case 3: mang.append('X').append('0'); break;
            case 4: mang.append('X'); break;
            }
            mang.append(xstr);
        }
        if (mang.length() == s.length() && mang.indexOf(s) == 0)
            return s;  // no change
        return mang.toString();
    }

    // Functions
    private static Object SF_list(Object x, Object y) {
        return Arrays.asList(x, y);
    }
    private static Object SF_list(Object... xs) {
        return Arrays.asList(xs);
    }
    private static Object SF_list_tail(Object x, int n) {
        List ls = (List) x;
        return ls.subList(n, ls.size());
    }
    private static String SF_symbol_Gstring(Object x) {
        return ((SIOC)x).toString();
    }
    private static Object SF_string_Gsymbol(String x) {
        return new SIOC(KIND_SYMBOL, x.intern());
    }
    private static boolean SF_symbolQ(Object x) {
        return x instanceof SIOC && ((SIOC)x).kind == KIND_SYMBOL;
    }
    private static boolean SF_stringQ(Object x) {
        return x instanceof CharSequence;  // ??
    }
    private static boolean SF_procedureQ(Object x) {
        return x instanceof MethodHandle;
    }
    private static String stringOrSymbol(Object x) {
        if (SF_symbolQ(x) || SF_stringQ(x))
            return x.toString();
        throw toRTE("not a symbol or string: "+SF_display_to_string(x));
    }

    // arithmetic
    private static int SF_P(int x, int y) { return x + y; }
    private static int SF__(int x, int y) { return x - y; }
    private static int SF__(       int y) { return   - y; }
    private static int SF_S(int x, int y) { return x * y; }
    private static int SF_D(int x, int y) { return x / y; }
    private static long SF_P(long x, long y) { return x + y; }
    private static long SF__(long x, long y) { return x - y; }
    private static long SF__(        long y) { return   - y; }
    private static long SF_S(long x, long y) { return x * y; }
    private static long SF_D(long x, long y) { return x / y; }
    private static double SF_P(double x, double y) { return x + y; }
    private static double SF__(double x, double y) { return x - y; }
    private static double SF__(          double y) { return   - y; }
    private static double SF_S(double x, double y) { return x * y; }
    private static double SF_D(double x, double y) { return x / y; }
    
    private static MethodHandle SF_Tbind_left(MethodHandle f, Object x) {
        return bindCarefully(f, x);
    }
    private static MethodHandle SF_Tbind_right(MethodHandle f, Object x) {
        return insertArguments(f, f.type().parameterCount()-1, x);
    }
    private static MethodHandle SF_Tcompose(MethodHandle f, MethodHandle g) {
        if (g.type().parameterCount() == 1)
            return filterArguments(f, 0, g);
        return filterReturnValue(g, f);
    }
    private static MethodHandle SF_Tcompose(MethodHandle f, int pos, MethodHandle g) {
        return filterArguments(f, pos, g);
    }
    private static MethodType SF_Tmethod_type(Class rtype, Object... ptypes) {
        return methodType(rtype, Arrays.copyOf(ptypes, ptypes.length, Class[].class));
    }

    // conversions
    private MethodHandle toMethodHandle(Object x) {
        if (x instanceof MethodHandle)
            return (MethodHandle) x;
        throw toRTE("not a procedure: "+x);
    }
    private Reader toReader(Object x) {
        if (x instanceof InputStream)
            return new InputStreamReader((InputStream)x);
        return (Reader) x;
    }
    private Writer toWriter(Object x) {
        if (x instanceof OutputStream)
            return new PrintWriter((OutputStream)x, true);
        return (Writer) x;
    }
    private static RuntimeException toRTE(String x) {
        return new RuntimeException(x);
    }
    private static InternalError toIE(String x, Throwable ex) {
        InternalError err = new InternalError(x);
        err.initCause(ex);
        return err;
    }
}
