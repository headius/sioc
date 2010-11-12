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

import java.util.*;
import java.io.*;
import java.dyn.*;
import java.lang.reflect.*;
import static java.dyn.MethodType.*;
import static java.dyn.MethodHandles.*;

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
 * Scheme in One Class
 * This is a small demonstration of JSR 292 APIs.
 * This project will stay within one class file.
 * <p>
 * Day One:  A REPL that can (print "hello").
 * @see http://cr.openjdk.java.net/~jrose/pres/indy-javadoc-mlvm/
 * @author John Rose
 */
class SIOC {
    public static void main(String... args) throws Throwable {
        new SIOC().run(args);
    }
    public void run(String... args) throws Throwable {
        List<String> av = new ArrayList<>(Arrays.asList(args));
        boolean didRun = false;
        while (!av.isEmpty()) {
            String a = av.remove(0);
            set("arguments", av);
            switch (a) {
            case "-f": F_load(av.remove(0)); didRun = true; continue;
            case "-e": F_load_from_string(av.remove(0)); didRun = true; continue;
            case "-i": set("arguments", av); interactiveREPL(); return;
            default: break;
            }
            if (a.startsWith("-"))  throw toRTE("bad flag: "+a);
            break;
        }
        if (!(didRun && av.isEmpty())) {
            set("arguments", av);
            interactiveREPL();
        }
    }

    public SIOC() {
        kind = KIND_NONE;
        name = null;
    }

    private static final int
        KIND_NONE = 0, KIND_SYMBOL = 1, KIND_SPECIAL = 2;
    private final int kind;
    private final String name;

    private SIOC(int kind, String name) {
        this.kind = kind;
        this.name = name;
    }

    public String toString() {
        if (name != null)  return name;
        return super.toString();
    }

    public int hashCode() {
        if (name != null)  return name.hashCode();
        return super.hashCode();
    }

    public boolean equals(Object x) {
        if (kind != KIND_NONE && x instanceof SIOC) {
            SIOC that = (SIOC) x;
            return this.kind == that.kind && (Object) this.name == that.name;
        }
        return this == x;
    }

    private Map<String, Object> values;
    private Map<String, Object> values() {
        if (values == null)
            return values = new HashMap<>();
        return values;
    }

    public void setDefault(String name, Object x) {
        if (values().containsKey(name))  return;
        values().put(name, x);
    }
    public void set(String name, Object x) {
        values().put(name, x);
    }
    private Object getValue(String name) {
        if (values == null)  return null;
        return values.get(name);
    }

    private static final Object
        F_HBend_of_file = special("#!end-of-file"),
        F_HBend_of_list = special("#!end-of-list"),
        F_HBdefault_object = special("#!default-object"),
        F_null = special("null")
        ;
    private static final Object
        SP_dot = special("."),
        S_begin = F_string_Gsymbol("begin"),
        S_quote = F_string_Gsymbol("quote"),
        S_setB = F_string_Gsymbol("set!"),
        S_quasiquote = F_string_Gsymbol("quasiquote"),
        S_unquote = F_string_Gsymbol("unquote"),
        S_unquote_splicing = F_string_Gsymbol("unquote-splicing");
    private static Object special(String s) {
        return new SIOC(KIND_SPECIAL, s);
    }
    void interactiveREPL() throws Throwable {
        // Set up default bindings:
        setDefault("banner", ";; SIOC");
        setDefault("trailer", ";; exit\n");
        setDefault("prompt", "\n> ");
        setDefault("input", toReader(System.in));
        setDefault("output", toWriter(System.out));
        setDefault("error-output", toWriter(System.err));
        F_display(get("banner"), get("error-output"));
        for (;;) {
            F_display(get("prompt"), get("error-output"));
            Object x;
            try {
                x = F_read();
                if (x == F_HBend_of_file)
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
    private String F_display_to_string(Object x) throws Throwable {
        StringWriter port = new StringWriter();
        unparse(x, port, false);
        return port.toString();
    }

    private void F_print(Object x) throws Throwable { F_print(x, get("output")); }
    private void F_print(Object x, Object port) throws Throwable {
        unparse(x, toWriter(port), true);
    }
    private String F_print_to_string(Object x) throws Throwable {
        StringWriter port = new StringWriter();
        unparse(x, port, true);
        return port.toString();
    }

    private void F_newline() throws Throwable { F_newline(get("output")); }
    private void F_newline(Object port) throws Throwable {
        toWriter(port).write("\n");
    }

    private void F_load(File file) throws Throwable {
        BufferedReader port = new BufferedReader(new FileReader(file));
        try {
            F_load(port);
        } finally {
            port.close();
        }
    }
    private void F_load(Reader port) throws Throwable {
        int[] nextc = {NONE};
        for (;;) {
            Object x = parse(port, nextc);
            if (x == F_HBend_of_file)  break;
            F_eval(x);
        }
    }
    private void F_load(Object source) throws Throwable {
        if (source instanceof String)
            F_load(new File((String)source));
        else if (source instanceof File)
             F_load((File)source);
        else
            F_load(toReader(source));
    }
    private void F_load_from_string(String exp) throws Throwable {
        F_load(new StringReader(exp));
    }

    private Object F_eval(Object exp) throws Throwable {
        if (exp instanceof List) {
            List<Object> forms = (List<Object>) exp;
            if (forms.isEmpty())  return exp;  // () self-evaluates
            Object head = forms.get(0);
            if (F_symbolQ(head)) {
                Object sym = head;
                head = get(F_symbol_Gstring(sym));
                if (!F_procedureQ(head)) {
                    // special cases
                    if (S_quote.equals(sym) && forms.size() == 2) {
                        return forms.get(1);
                    }
                    if (S_setB.equals(sym) && forms.size() == 3 &&
                        F_symbolQ(forms.get(1))) {
                        Object var = forms.get(1);
                        Object val = F_eval(forms.get(2));
                        set(F_symbol_Gstring(var), val);
                        return null;
                    }
                    // no regular binding for the head symbol; compile it
                    return F_eval(F_compile(exp));
                }
            } else {
                head = F_eval(head);
            }
            Object[] args = forms.subList(1, forms.size()).toArray();
            for (int i = 0; i < args.length; i++) {
                args[i] = F_eval(args[i]);
            }
            return toMethodHandle(head).invokeWithArguments(args);
        } else if (F_symbolQ(exp)) {
            Object x = get(F_symbol_Gstring(exp));
            if (x == null)  throw toRTE("unbound: "+F_print_to_string(exp));
            return x;
        } else {
            return exp;  // self-evaluating
        }
    }

    public Object get(String name) throws Throwable {
        Object x;
        x = getValue(name);
        if (x != null) {
            return x;
        }
        if (values().containsKey(name))
            return F_null;
        x = lookupVirtual(name);
        if (x != null)
            return x;
        return lookupStatic(name);
    }

    private Object F_compile(Object exp) throws Throwable {
        throw toRTE("cannot compile: "+limit(F_print_to_string(exp)));
    }
    private String limit(String x) {
        if (x.length() > 100)
            return x.substring(0, 80).concat(" ...");
        return x;
    }

    // parsing

    private static final int EOF = -1, NONE = -2;

    private Object parse(Reader port, int[] nextc) throws IOException {
        int c = nextc[0];
        Object x;
        nextc[0] = NONE;
    restart:
        for (;;) {
            switch (c) {
            case NONE: c = port.read(); continue restart;
            case '(':
                for (ArrayList<Object> xs = new ArrayList<>();;) {
                    x = parse(port, nextc);
                    if (x == SP_dot) {
                        x = parse(port, nextc);
                        if (x instanceof List) {
                            xs.addAll((List<Object>)x);
                            continue;
                        }
                        xs.add(SP_dot);
                    }
                    if (x == F_HBend_of_list)
                        return xs;
                    xs.add(x);
                }
            case ')': return F_HBend_of_list;
            case EOF: return F_HBend_of_file;
            case ';':
                for (;;) {
                    c = port.read();
                    switch (c) {
                    case EOF: case '\n': case '\r':
                        continue restart;
                    }
                }
            case '"': return parseQuoted(port, '"');
            case '|': return F_string_Gsymbol(parseQuoted(port, '|'));
            case '\'': return F_list(S_quote, parse(port, nextc));
            case '`': return F_list(S_quasiquote, parse(port, nextc));
            case ',':
                c = port.read();
                if (c == '@')
                    return F_list(S_unquote_splicing, parse(port, nextc));
                nextc[0] = c;
                return F_list(S_unquote, parse(port, nextc));
            case '#':
                c = port.read();
                switch (c) {
                case '(':
                    nextc[0] = '(';
                    return ((List<Object>)parse(port, nextc)).toArray();
                case ';':
                    x = parse(port, nextc);
                    if (x == F_HBend_of_file)  return x;
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

    private Object parseIdent(Reader port, int[] nextc, StringBuilder cs) throws IOException {
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
        return F_string_Gsymbol(s);
    }

    private char parseCharName(Reader port, int[] nextc, StringBuilder cs) throws IOException {
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
                int code = Integer.parseInt(s.substring(1, s.length()), 16);
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

    private void unparse(Object x, Writer port, boolean isPrint) throws IOException {
        boolean didit = false;
        if (x instanceof List) {
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
        } else if (isPrint && F_symbolQ(x)) {
            String s = F_symbol_Gstring(x);
            if (specialIdent(s) != null || hasSpecialChar(s))
                unparseQuoted(s, port, '|');
            else
                port.write(s);
            didit = true;
        } else if (isPrint && x instanceof String) {
            unparseQuoted((String)x, port, '"');
            didit = true;
        } else if (x instanceof Character) {
            char c = (char) x;
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
        case ".": return SP_dot;
        case "": return F_HBend_of_file;
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
    private static final HashMap<String,Object> STATIC_CACHE = new HashMap<>();
    private static Object lookupStatic(String name) throws Throwable {
        Object x = STATIC_CACHE.get(name);
        if (x == null) {
            x = lookupReflective(name, true);
            if (x == null)  return null;
            STATIC_CACHE.put(name, x);
        }
        return x;
    }
    private static final HashMap<String,MethodHandle> VIRTUAL_CACHE = new HashMap<>();
    private MethodHandle lookupVirtual(String name) throws Throwable {
        MethodHandle mh = VIRTUAL_CACHE.get(name);
        if (mh == null) {
            mh = (MethodHandle) lookupReflective(name, false);
            if (mh == null)  return null;
            VIRTUAL_CACHE.put(name, mh);
        }
        return bindCarefully(mh, this);
    }
    private static final Method[] SIOC_METHODS = filterMembers(SIOC.class.getDeclaredMethods());
    private static final Field[] SIOC_FIELDS = filterMembers(SIOC.class.getDeclaredFields());
    private static final Class<?> VARARGS_TYPE = Object[].class;  // local marker for VA methods
    private static <T extends Member> T[] filterMembers(T[] mems0) {
        ArrayList<T> mems = new ArrayList<>(Arrays.asList(mems0));
        for (Iterator<T> i = mems.iterator(); i.hasNext(); ) {
            if (!i.next().getName().startsWith("F_"))
                i.remove();
        }
        return mems.toArray(Arrays.copyOf(mems0, mems.size()));
    }

    private static final Lookup LOOKUP = MethodHandles.lookup();
    private static final MethodHandle MH_chooseMethod;
    private static final MethodHandle MH_vaTypeHandler;
    private static final MethodHandle MH_bindTypeHandler;
    private static final Comparator<Class<?>> C_compareClasses;
    private static final Comparator<MethodHandle> C_compareMethodHandles;
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
            MethodType C_type = methodType(int.class,
                                           Object.class, Object.class);
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
        } catch (ReflectiveOperationException ex) {
            Error err = new InternalError("bad lookup");
            err.initCause(ex);
            throw err;
        }
    }

    private static Object lookupReflective(String name, boolean isStatic) throws Throwable {
        String mang = mangle(name);  // e.g., F_list
        if (mang == null)  return null;
        Object x = null;
        for (Field f : SIOC_FIELDS) {
            if (!matchMember(f, mang, isStatic))  continue;
            x = f.get(null);
            break;
        }
        MethodHandle mh = null;
        List<MethodHandle> mhs = null;
        if (x != null) {
            if (x instanceof MethodHandle)
                mh = (MethodHandle) x;  // go for more overloads
            else if (isStatic)
                return x;
        }
        for (Method m : SIOC_METHODS) {
            if (!matchMember(m, mang, isStatic))  continue;
            MethodHandle mh1 = LOOKUP.unreflect(m);
            boolean isva = isVarArgs(mh1.type(), VARARGS_TYPE);
            if (isva != m.isVarArgs())  throw toRTE("bad varargs: "+m);
            if (mh == null) { mh = mh1; continue; }
            if (mhs == null) { mhs = new ArrayList<>(); mhs.add(mh); }
            mhs.add(mh1);
        }
        if (mhs == null) {
            return mh;  // null or unique
        }
        assert(mhs.contains(mh));
        return overload(mhs, VARARGS_TYPE);
    }
    private static boolean matchMember(Member m, String name, boolean isStatic) {
        return (m.getName().equals(name)
                && isStatic == Modifier.isStatic(m.getModifiers()));
    }

    // mh.bindTo(x) but preserving type handler (variadic properties)
    public static MethodHandle bindCarefully(MethodHandle mh, Object x) {
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

    public static MethodHandle overload(List<MethodHandle> mhs, Class<?> varargsType) throws Throwable {
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
    private static <T> void addIfNotNull(List<T> ls, T x) {
        if (x != null)  ls.add(x);
    }
    public static boolean isVarArgs(MethodType type, Class<?> varargsType) {
        if (varargsType == null)  return false;
        int ac = type.parameterCount();
        return (ac > 0 && type.parameterType(ac-1) == varargsType);
    }

    public static MethodHandle adjustArity(MethodHandle mh, int ac) {
        MethodType type = mh.type();
        int fixedArgs = type.parameterCount() - 1;
        int collectArgs = ac - fixedArgs;
        assert(fixedArgs > 0 && collectArgs >= 0);
        Class<?> vap = type.parameterType(fixedArgs);  // varargs param
        Class<?> ptype = vap.getComponentType();
        if (ptype == null) {
            ptype = Object.class;
            vap = Object[].class;
            type = type.changeParameterType(fixedArgs, vap);
            mh = explicitCastArguments(mh, type);
        }
        // convert (A..., P[]) to (A..., P...)
        return mh.asCollector(vap, collectArgs);
    }
    public static MethodHandle adjustArity(MethodHandle mh, MethodType type) {
        return adjustArity(mh, type.parameterCount()).asType(type);
    }

    public static MethodHandle arityOverload(List<MethodHandle> mhs, MethodHandle vamh) {
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

    private static final String MANGLE_CHARS = "A@B!C:D/E=G>H#L<M&P+Q?T*V^";
    //  also: "_-X0Ua": X hex, U upper
    private static String mangle(String s) {
        StringBuilder mang = new StringBuilder("F_");
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
            if (Character.isJavaLetterOrDigit(c)) {
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
        return mang.toString();
    }

    // Functions
    private static Object F_list(Object x, Object y) {
        return Arrays.asList(x, y);
    }
    private static Object F_list(Object... xs) {
        return Arrays.asList(xs);
    }
    private static String F_symbol_Gstring(Object x) {
        return ((SIOC)x).toString();
    }
    private static Object F_string_Gsymbol(String x) {
        return new SIOC(KIND_SYMBOL, x.intern());
    }
    private static boolean F_symbolQ(Object x) {
        return x instanceof SIOC && ((SIOC)x).kind == KIND_SYMBOL;
    }
    private static boolean F_procedureQ(Object x) {
        return x instanceof MethodHandle;
    }

    // arithmetic
    private static int F_P(int x, int y) { return x + y; }
    private static int F__(int x, int y) { return x - y; }
    private static int F__(       int y) { return   - y; }
    private static int F_T(int x, int y) { return x * y; }
    private static int F_D(int x, int y) { return x / y; }
    private static long F_P(long x, long y) { return x + y; }
    private static long F__(long x, long y) { return x - y; }
    private static long F__(        long y) { return   - y; }
    private static long F_T(long x, long y) { return x * y; }
    private static long F_D(long x, long y) { return x / y; }
    private static double F_P(double x, double y) { return x + y; }
    private static double F__(double x, double y) { return x - y; }
    private static double F__(          double y) { return   - y; }
    private static double F_T(double x, double y) { return x * y; }
    private static double F_D(double x, double y) { return x / y; }

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
    private String toDisplayString(Object x) throws Throwable {
        return F_display_to_string(x);
    }
    private String toPrintString(Object x) throws Throwable {
        return F_print_to_string(x);
    }
    private static RuntimeException toRTE(String x) {
        return new RuntimeException(x);
    }
}
