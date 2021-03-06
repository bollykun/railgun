package org.railgun.vm;

import org.railgun.Controls;
import org.railgun.canvas.View;
import org.railgun.marshal.BinaryFileParser;
import org.railgun.marshal.CodeObject;
import org.railgun.shape.*;
import org.railgun.vm.intrisinc.*;
import org.railgun.vm.object.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

public class Interpreter {

    private static Interpreter instance = new Interpreter();

    private static boolean x86 = false;

    private Interpreter() {
        builtinsTables.put("True", Boolean.TRUE);
        builtinsTables.put("False", Boolean.FALSE);

        builtinsTables.put("circle", new CircleMethod());
        builtinsTables.put("rgb", new RgbMethod());

        builtinsTables.put("line", new RGLineMethod());
        builtinsTables.put("roundrect", new RGRoundRectMethod());
        builtinsTables.put("rect", new RGRectMethod());

        builtinsTables.put("rgtext", new RGTextMethod());
        builtinsTables.put("star", new StarMethod());

        builtinsTables.put("random", new RandomMethod());
        builtinsTables.put("camera", new CameraMethod());

        builtinsTables.put("len", new LenMethod());

        builtinsTables.put("addTimer", new AddTimerMethod());

        builtinsTables.put("setKeyMap", new KeyMapMethod());
        builtinsTables.put("setMouseMap", new MouseMapMethod());
        builtinsTables.put("setUpdate", new UpdateFunctionMethod());
        builtinsTables.put("setFrameCount", new FrameCountMethod());
    }

    public static Interpreter getInstance() {
        return instance;
    }

    // Global variable table
    private Map<String, Object> builtinsTables = new HashMap<>();

    // Run with source bytes
    public void run (byte[] sourceBytes) {
        // Read code object from bytecode
        CodeObject co = BinaryFileParser.parse(sourceBytes);

        run(co);
    }

    // Run with code object
    private void run (CodeObject co) {
        Map<String, Object> globalsTable = new HashMap<>();
        globalsTable.put("__name__", "__main__");

        // Construct base frame
        RGFrameObject baseFrame = new RGFrameObject(co, globalsTable, globalsTable, 0);

        // Interpret current frame
        interpret(baseFrame, new Stack<>());
    }

    public void run(RGFunctionObject fo, Object ... args) {
        // Construct base frame
        RGFrameObject baseFrame = new RGFrameObject(fo.co, new HashMap<>(), fo.globalsTable, 0);

        assert args.length == fo.argcount;

        // Construct arguments
        for (int i = 0; i < fo.argcount; ++i) {
            baseFrame.frameLocalsTable.put((String) fo.co.varnames.get(i), args[i]);
        }

        // Interpret current frame
        interpret(baseFrame, new Stack<>());
    }

    // Interpret Instructions
    void interpret (RGFrameObject curFrame, Stack<RGFrameObject> stackTrace) {
        // Program Counter
        int pc = curFrame.pc;
        // Bytecodes Array
        byte[] optArr = curFrame.optArr;
        // Bytecode Array Length
        int optLength = optArr.length;

        // Bytecode constant pools
        List<Object> consts = curFrame.consts;
        // Bytecode local variable
        List<Object> varnames = curFrame.varnames;
        Map<String, Object> localsTable = curFrame.frameLocalsTable;

        // Bytecode global variable
        List<Object> names = curFrame.names;
        Map<String, Object> globalsTable = curFrame.frameGlobalsTable;

        Stack<Object> stack = curFrame.frameStack;
        Stack<Integer> blockStack = curFrame.blockStack;

        while (pc < optLength) {
            // TODO: Make sure current pc is at optcode, not optarg
            byte optcode = optArr[pc++];
            boolean haveArgument = (optcode & 0xFF) >= Bytecode.HAVE_ARGUMENT;
            int optarg = -1;
            if (haveArgument) {
                optarg = x86 ? (optArr[pc++] & 0xFF) : ((optArr[pc++] & 0xFF) + ((optArr[pc++] & 0xFF) << 8));
            }
            Integer lhs, rhs;
            Object v, w, u, attr;
            switch (optcode) {
                case Bytecode.POP_TOP:
                    stack.pop();
                    break;
                // 2
                case Bytecode.ROT_TWO:
                    v = stack.pop();
                    w = stack.pop();
                    stack.push(v);
                    stack.push(w);
                    break;

                case Bytecode.ROT_THREE:
                    v = stack.pop();
                    w = stack.pop();
                    u = stack.pop();

                    stack.push(v);
                    stack.push(u);
                    stack.push(w);
                    break;

                // 4
                case Bytecode.DUP_TOP:
                    stack.push(stack.peek());
                    break;

                // 99
                case Bytecode.DUP_TOPX:
                    if (optarg == 2) {
                        v = stack.peek();
                        w = stack.get(stack.size() - 2);
                        stack.push(w);
                        stack.push(v);
                    }
                    else if (optarg == 3) {
                        v = stack.peek();
                        w = stack.get(stack.size() - 2);
                        u = stack.get(stack.size() - 3);
                        stack.push(u);
                        stack.push(w);
                        stack.push(v);
                    }
                    break;

                case Bytecode.UNARY_NEGATIVE:
                    v = stack.pop();
                    stack.push(-((Integer)v).intValue());
                    break;
                // 59
                case Bytecode.INPLACE_MODULO:
                // 22
                case Bytecode.BINARY_MODULO:
                    v = stack.pop();
                    w = stack.pop();
                    /* w % v */
                    stack.push(((Integer)w) % ((Integer)v));
                    break;
                // 25
                case Bytecode.BINARY_SUBSCR:
                    v = stack.pop();
                    w = stack.pop();
                    if(w instanceof RGObject) {
                        stack.push(((BuiltinMethodObject)(((RGObject)w).getAttr("get"))).call((Integer)v)) ;
                    }
                    else
                    {
                        HashMap<Object, Object> mmap = (HashMap<Object, Object>)w;
                        stack.push(mmap.get(v));
                    }
                    break;

                // 57
                case Bytecode.INPLACE_MULTIPLY:
                // 20
                case Bytecode.BINARY_MULTIPLY:
                    rhs = (Integer) stack.pop();
                    lhs = (Integer) stack.pop();
                    stack.push(lhs * rhs);
                    break;
                // 58
                case Bytecode.INPLACE_DIVIDE:
                // 21
                case Bytecode.BINARY_DIVIDE:
                    rhs = (Integer) stack.pop();
                    lhs = (Integer) stack.pop();
                    stack.push(lhs / rhs);
                    break;
                // 55
                case Bytecode.INPLACE_ADD:
                // 23
                case Bytecode.BINARY_ADD:
                    rhs = (Integer) stack.pop();
                    lhs = (Integer) stack.pop();
                    stack.push(lhs + rhs);
                    break;
                // 56
                case Bytecode.INPLACE_SUBSTRACT:
                    // 24
                case Bytecode.BINARY_SUBSTRACT:
                    rhs = (Integer) stack.pop();
                    lhs = (Integer) stack.pop();
                    stack.push(lhs - rhs);
                    break;
                // 60
                case Bytecode.STORE_SUBSCR:
                    w = stack.pop();
                    v = stack.pop();
                    u = stack.pop();
                    /* v[w] = u */
                    if (v instanceof RGObject) {
                        ((BuiltinMethodObject)((RGObject)v).getAttr("set")).call((Integer) w, u);
                    } else if (v instanceof Map) {
                        ((Map) v).put(w, u);
                    }
                    break;
                // 68
                case Bytecode.GET_ITER:
                    v = stack.pop();
                    if (v instanceof Iterable) {
                        stack.push(((Iterable) v).iterator());
                    }
                    break;
                // 71
                case Bytecode.PRINT_ITEM:
                    Object printObject = stack.pop();

                    if (printObject instanceof RGObject) {
                        for (Object obj : ((LinkedList)(((RGObject)printObject).getAttr("elements")))) {
                            if (obj instanceof Shape) {
                                View.getView().drawShape((Shape)obj);
                            }
                        }
                    }
                    else if (printObject instanceof Shape) {
                        View.getView().drawShape((Shape) printObject);
                    }
                    else
                        System.out.println(printObject);

                    break;
                // 72
                case Bytecode.PRINT_NEWLINE:
                    break;
                // 80
                case Bytecode.BREAK_LOOP:
                    pc = blockStack.pop();
                    break;

                // 82
                case Bytecode.LOAD_LOCALS:
                    stack.push(localsTable);
                    break;
                // 83
                case Bytecode.RETURN_VALUE:
                    if (! stackTrace.empty()) {
                        curFrame = stackTrace.pop();
                        curFrame.frameStack.push(stack.pop());
                        consts = curFrame.consts;
                        optArr = curFrame.optArr;
                        names = curFrame.names;
                        varnames = curFrame.varnames;
                        localsTable = curFrame.frameLocalsTable;
                        globalsTable = curFrame.frameGlobalsTable;
                        stack = curFrame.frameStack;
                        blockStack = curFrame.blockStack;
                        pc = curFrame.pc;
                        optLength = optArr.length;
                    } else {
                        return;
                    }
                    break;
                // 87
                case Bytecode.POP_BLOCK:
                    blockStack.pop();
                    break;

                // 89
                case Bytecode.BUILD_CLASS:
                    Klass klass = new Klass((HashMap<String, Object>)stack.pop());
                    stack.push(klass);
                    break;

                // TODO: Have Argument
                // 95
                case Bytecode.STORE_ATTR:
                    v = stack.pop();
                    w = stack.pop();

                    attr = (String)names.get(optarg);

                    if (v instanceof Shape) {
                        if (attr.equals("x")) {
                            ((Shape)v).setX((Integer)w);
                        }
                        else if (attr.equals("y")) {
                            ((Shape)v).setY((Integer)w);
                        }
                    }
                    break;

                // 90
                // TODO: STORE_NAME 将变量存到 localsTable中，需要保证 这个指令出现时 localsTable == globalsTable
                case Bytecode.STORE_NAME:
                    assert localsTable == globalsTable;
                    v = names.get(optarg);
                    w = stack.pop();
                    localsTable.put((String)v, w);
                    break;
                // 97
                case Bytecode.STORE_GLOBAL:
                    v = names.get(optarg);
                    w = stack.pop();
                    globalsTable.put((String)v, w);
                    break;
                // 93
                case Bytecode.FOR_ITER:
                    v = stack.peek();
                    if (v instanceof Iterator) {
                        if (((Iterator) v).hasNext()) {
                            stack.push(((Iterator) v).next());
                        } else {
                            pc += optarg;
                            stack.pop();
                        }
                    }
                    break;
                // 101
                case Bytecode.LOAD_NAME:
                    v = names.get(optarg);

                    // LGB: Local -> Global -> Builtins
                    // TODO Assert: 运算顺序从左到右
                    if (((w = localsTable.get(v)) == null)
                            && ((w = globalsTable.get(v)) == null)
                            && ((w = builtinsTables.get(v)) == null)) {
                        throw new RuntimeException("Unknow name variable : " + v);
                    }

                    stack.push(w);
                    break;
                // 116
                case Bytecode.LOAD_GLOBAL:
                    v = names.get(optarg);

                    // LGB: Global -> Builtins
                    // TODO Assert: 运算顺序从左到右
                    if (((w = globalsTable.get(v)) == null)
                            && ((w = builtinsTables.get(v)) == null)) {
                        //throw new RuntimeException("Unknow global variable : " + v + " in " + curFrame.co.name);
                    }

                    stack.push(w);
                    break;
                // 100
                case Bytecode.LOAD_CONST:
                    stack.push(consts.get(optarg));
                    break;
                // 106
                case Bytecode.LOAD_ATTR:
                    v = stack.pop();
                    w = names.get(optarg);

                    if (v instanceof Shape) {
                        if (((String)w).equals("x")) {
                            stack.push(((Shape)v).getX());
                        }
                        else if (((String)w).equals("y")) {
                            stack.push(((Shape)v).getY());
                        }
                    }
                    else if (v instanceof RGObject) {
                        stack.push(((RGObject)v).getAttr((String)w));
                    }
                    else {
                        try {
                            Method method = v.getClass().getMethod((String)w, double.class);
                            stack.push(method);
                        } catch (NoSuchMethodException e) {
                            e.printStackTrace();
                        }
                    }
                    break;

                // 107
                case Bytecode.COMPARE_OP:
                    // TODO: Only for Integer
                    rhs = (Integer) stack.pop();
                    lhs = (Integer) stack.pop();
                    switch (optarg) {
                        case Bytecode.COMPARE.LESS:
                            stack.push(lhs < rhs);
                            break;
                        case Bytecode.COMPARE.LESS_EQUAL:
                            stack.push(lhs <= rhs);
                            break;
                        case Bytecode.COMPARE.EQUAL:
                            stack.push(lhs.intValue() == rhs.intValue());
                            break;
                        case Bytecode.COMPARE.NOT_EQUAL:
                            stack.push(lhs.intValue() != rhs.intValue());
                            break;
                        case Bytecode.COMPARE.GREATER:
                            stack.push(lhs > rhs);
                            break;
                        case Bytecode.COMPARE.GREATER_EQUAL:
                            stack.push(lhs >= rhs);
                            break;
                    }
                    break;
                // 110
                case Bytecode.JUMP_FORWARD:
                    pc += optarg;
                    break;

                // 111
                case Bytecode.JUMP_IF_FALSE_OR_POP:
                    if ((Boolean) stack.peek()) {
                        stack.pop();
                    }
                    else
                        pc = optarg;
                    break;

                // 113
                case Bytecode.JUMP_ABSOLUTE:
                    pc = optarg;
                    break;
                // 114
                case Bytecode.POP_JUMP_IF_FALSE:
                    if (! (Boolean) stack.pop())
                        pc = optarg;
                    break;
                // 115
                case Bytecode.POP_JUMP_IF_TRUE:
                    if ((Boolean) stack.pop())
                        pc = optarg;
                    break;
                // 120
                case Bytecode.SETUP_LOOP:
                    blockStack.push(pc + optarg);
                    break;
                // 124
                case Bytecode.LOAD_FAST:
                    v = varnames.get(optarg);
                    w = localsTable.get(v);
                    if (w == null) {
                        throw new RuntimeException("Unavailable local variable " + v);
                    }
                    stack.push(w);
                    break;
                // 125
                case Bytecode.STORE_FAST:
                    v = varnames.get(optarg);
                    w = stack.pop();
                    localsTable.put((String)v, w);
                    break;
                // 131
                case Bytecode.CALL_FUNCTION:
                    // Process Callee Arguments
                    Object[] nextArgs = new Object[optarg];
                    for (int i = 0; i < optarg; ++i) {
                       nextArgs[i] = stack.pop();
                    }
                    Object o = stack.pop();

                    if (o instanceof InnerMethod) {
                        stack.push(((InnerMethod)o).call(nextArgs));
                    }
                    else if (o instanceof BuiltinMethodObject) {
                        stack.push(((BuiltinMethodObject)o).call(nextArgs));
                    }
                    else if (o instanceof Method) {
                        try {
                            stack.push(((Method)o).invoke(Controls.getInstance().getCamera(), nextArgs));
                        } catch (IllegalAccessException e) {
                            e.printStackTrace();
                        } catch (InvocationTargetException e) {
                            e.printStackTrace();
                        }
                    }
                    else if (o instanceof Klass) {
                        stack.push(((Klass)o).allocate());
                    }
                    else {
                        // 保存上下文
                        curFrame.pc = pc;
                        stackTrace.push(curFrame);

                        RGFunctionObject fo = (RGFunctionObject) o;
                        int defaultcount = fo.defaultsTable.size();
                        int argcount = fo.argcount;
                        // 保证参数数目的正确性
                        if((optarg + defaultcount < argcount) || (optarg < argcount)) {
                            throw new RuntimeException("RGException: Function " + fo.name + " Need "
                            + argcount + " parameters, but input parameters number not match!");
                        }

                        curFrame = new RGFrameObject(fo.co, new HashMap<>(), globalsTable, 0);

                        // 切换上下文
                        consts = curFrame.consts;
                        names = curFrame.names;
                        varnames = curFrame.varnames;
                        localsTable = curFrame.frameLocalsTable;
                        globalsTable = curFrame.frameGlobalsTable;
                        optArr = curFrame.optArr;
                        stack = curFrame.frameStack;
                        blockStack = curFrame.blockStack;
                        pc = curFrame.pc;
                        optLength = optArr.length;
                        // 处理调用参数
                        // i -> stack args; j -> local args;
                        for (int i = optarg-1, j = 0; i >= 0; --i, ++j) {
                            localsTable.put((String) varnames.get(j), nextArgs[i]);
                        }
                        // 处理默认参数
                        // i -> default args; j ->  local args;
                        for (int i = fo.argcount-optarg-1, j = optarg; j < fo.argcount; --i, ++j) {
                            localsTable.put((String) varnames.get(j), fo.defaultsTable.get(i));
                        }
                    }
                    break;
                // 132
                case Bytecode.MAKE_FUNCTION:
                    CodeObject co = (CodeObject) stack.pop();  // Code Object
                    List<Object> defaultTable = new ArrayList<>(optarg);
                    // process default arguments
                    for(int i = 0; i < optarg; ++i) {
                        defaultTable.add(stack.pop());
                    }
                    RGFunctionObject functionObject = new RGFunctionObject(co, globalsTable, defaultTable);
                    stack.push(functionObject);
                    break;
                case Bytecode.BUILD_LIST:
                    RGObject arr = ListKlass.getListKlass().allocate();
                    for (int i = 0; i < optarg; ++i) {
                        ((BuiltinMethodObject)arr.getAttr("addFirst")).call(stack.pop());
                    }
                    stack.push(arr);
                    break;
                //105
                case Bytecode.BUILD_MAP:
                    HashMap<Object, Object> mp = new HashMap<Object, Object>();
                    stack.push(mp);
                    break;
                //54
                case Bytecode.STORE_MAP:
                    Object objLeft = stack.pop();
                    Object objRight = stack.pop();
                    //mp.put(objLeft, objRight);
                    HashMap<Object, Object> map = (HashMap<Object, Object>) (stack.peek());
                    map.put(objLeft, objRight);
                    break;

                default:
                    throw new RuntimeException("Unimplemented bytecode : " + optcode);
            }
        }
    }

}

