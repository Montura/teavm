/*
 *  Copyright 2017 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.backend.llvm.rendering;

import static org.teavm.backend.common.Mangling.mangleField;
import static org.teavm.backend.common.Mangling.mangleMethod;
import static org.teavm.backend.common.RuntimeMembers.ARRAY_SIZE_FIELD;
import static org.teavm.backend.common.RuntimeMembers.CLASS_CLASS;
import static org.teavm.backend.common.RuntimeMembers.CLASS_FLAGS_FIELD;
import static org.teavm.backend.common.RuntimeMembers.OBJECT_CLASS_REFERENCE_FIELD;
import static org.teavm.backend.llvm.rendering.LLVMRenderingHelper.classInitializer;
import static org.teavm.backend.llvm.rendering.LLVMRenderingHelper.classInstance;
import static org.teavm.backend.llvm.rendering.LLVMRenderingHelper.classStruct;
import static org.teavm.backend.llvm.rendering.LLVMRenderingHelper.dataStruct;
import static org.teavm.backend.llvm.rendering.LLVMRenderingHelper.isInstanceFunction;
import static org.teavm.backend.llvm.rendering.LLVMRenderingHelper.methodType;
import static org.teavm.backend.llvm.rendering.LLVMRenderingHelper.renderItemType;
import static org.teavm.backend.llvm.rendering.LLVMRenderingHelper.renderType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.teavm.backend.common.Mangling;
import org.teavm.backend.llvm.LayoutProvider;
import org.teavm.interop.Address;
import org.teavm.model.BasicBlockReader;
import org.teavm.model.ClassReader;
import org.teavm.model.ClassReaderSource;
import org.teavm.model.ElementModifier;
import org.teavm.model.FieldReference;
import org.teavm.model.IncomingReader;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodHandle;
import org.teavm.model.MethodReader;
import org.teavm.model.MethodReference;
import org.teavm.model.PhiReader;
import org.teavm.model.ProgramReader;
import org.teavm.model.RuntimeConstant;
import org.teavm.model.TextLocation;
import org.teavm.model.ValueType;
import org.teavm.model.VariableReader;
import org.teavm.model.classes.StringPool;
import org.teavm.model.classes.VirtualTableEntry;
import org.teavm.model.classes.VirtualTableProvider;
import org.teavm.model.instructions.ArrayElementType;
import org.teavm.model.instructions.BinaryBranchingCondition;
import org.teavm.model.instructions.BinaryOperation;
import org.teavm.model.instructions.BranchingCondition;
import org.teavm.model.instructions.CastIntegerDirection;
import org.teavm.model.instructions.InstructionReader;
import org.teavm.model.instructions.IntegerSubtype;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.instructions.NumericOperandType;
import org.teavm.model.instructions.SwitchTableEntryReader;
import org.teavm.model.util.TypeInferer;
import org.teavm.model.util.VariableType;
import org.teavm.runtime.Allocator;
import org.teavm.runtime.RuntimeArray;
import org.teavm.runtime.RuntimeClass;

public class LLVMMethodRenderer {
    private ClassReaderSource classSource;
    private StringPool stringPool;
    private LayoutProvider layoutProvider;
    private VirtualTableProvider vtableProvider;
    private LLVMBlock rootBlock;
    private TypeInferer typeInferer;
    private LLVMBlock out;
    private int temporaryVariable;
    private int temporaryBlock;
    private MethodReader method;
    private String[] basicBlockLabels;
    private List<Runnable> deferredActions = new ArrayList<>();
    private Set<ValueType> referencedTypes = new HashSet<>();

    public LLVMMethodRenderer(ClassReaderSource classSource, StringPool stringPool, LayoutProvider layoutProvider,
            VirtualTableProvider vtableProvider) {
        this.classSource = classSource;
        this.stringPool = stringPool;
        this.layoutProvider = layoutProvider;
        this.vtableProvider = vtableProvider;
    }

    public Set<ValueType> getReferencedTypes() {
        return referencedTypes;
    }

    public void setRootBlock(LLVMBlock rootBlock) {
        this.rootBlock = rootBlock;
    }

    public void renderMethod(MethodReader method) {
        if (method.hasModifier(ElementModifier.NATIVE) || method.hasModifier(ElementModifier.ABSTRACT)) {
            return;
        }

        this.method = method;

        ProgramReader program = method.getProgram();
        typeInferer = new TypeInferer();
        typeInferer.inferTypes(program, method.getReference());

        renderSignature(method);
        basicBlockLabels = new String[program.basicBlockCount()];
        for (int i = 0; i < program.basicBlockCount(); ++i) {
            BasicBlockReader block = program.basicBlockAt(i);
            basicBlockLabels[i] = block(block);
            renderBlock(block);
        }

        for (Runnable deferredAction : deferredActions) {
            deferredAction.run();
        }
        deferredActions.clear();
    }

    private void renderSignature(MethodReader method) {
        StringBuilder sb = new StringBuilder();
        sb.append("define ").append(renderType(method.getResultType())).append(" ");
        sb.append("@").append(mangleMethod(method.getReference())).append("(");
        List<String> parameters = new ArrayList<>();
        if (!method.hasModifier(ElementModifier.STATIC)) {
            parameters.add("i8* %v0");
        }
        for (int i = 0; i < method.parameterCount(); ++i) {
            String type = renderType(method.parameterType(i));
            parameters.add(type + " %v" + (i + 1));
        }
        sb.append(parameters.stream().collect(Collectors.joining(", "))).append(") {");
        rootBlock.line(sb.toString());
    }

    private void renderBlock(BasicBlockReader block) {
        rootBlock.line(block(block) + ":");
        renderPhis(block);

        out = rootBlock.innerBlock();
        block.readAllInstructions(reader);
    }

    private void renderPhis(BasicBlockReader block) {
        if (block.readPhis().isEmpty()) {
            return;
        }

        LLVMBlock phiBlock = rootBlock.innerBlock();
        deferredActions.add(() -> {
            out = phiBlock;
            for (PhiReader phi : block.readPhis()) {
                String type = renderType(typeInferer.typeOf(phi.getReceiver().getIndex()));

                StringBuilder sb = new StringBuilder();
                sb.append("phi " + type);
                boolean first = true;
                for (IncomingReader incoming : phi.readIncomings()) {
                    if (!first) {
                        sb.append(", ");
                    }
                    first = false;
                    sb.append(" [ " + var(incoming.getValue()) + ", " + block(incoming.getSource()) + " ]");
                }

                assignTo(phi.getReceiver(), sb.toString());
            }
        });
    }

    private InstructionReader reader = new InstructionReader() {
        @Override
        public void location(TextLocation location) {
        }

        @Override
        public void nop() {
        }

        @Override
        public void classConstant(VariableReader receiver, ValueType cst) {
            referencedTypes.add(cst);
            assignTo(receiver, "bitcast i8* " + classInstance(cst) + " to i8*");
        }

        @Override
        public void nullConstant(VariableReader receiver) {
            assignTo(receiver, "bitcast i8* null to i8*");
        }

        @Override
        public void integerConstant(VariableReader receiver, int cst) {
            assignTo(receiver, "add i32 " + cst + ", 0");
        }

        @Override
        public void longConstant(VariableReader receiver, long cst) {
            assignTo(receiver, "add i64 " + cst + ", 0");
        }

        @Override
        public void floatConstant(VariableReader receiver, float cst) {
            assignTo(receiver, "fadd float " + cst + ", 0.0");
        }

        @Override
        public void doubleConstant(VariableReader receiver, double cst) {
            assignTo(receiver, "fadd double " + cst + ", 0.0");
        }

        @Override
        public void stringConstant(VariableReader receiver, String cst) {
            int index = stringPool.lookup(cst);
            assignTo(receiver, "bitcast %class.java.lang.String* @teavm.str." + index + " to i8*");
        }

        @Override
        public void binary(BinaryOperation op, VariableReader receiver, VariableReader first, VariableReader second,
                NumericOperandType type) {
            StringBuilder sb = new StringBuilder();
            boolean isFloat = type == NumericOperandType.FLOAT || type == NumericOperandType.DOUBLE;
            String typeStr = renderType(type);

            String secondString = var(second);
            switch (op) {
                case ADD:
                    sb.append(isFloat ? "fadd" : "add");
                    break;
                case SUBTRACT:
                    sb.append(isFloat ? "fsub" : "sub");
                    break;
                case MULTIPLY:
                    sb.append(isFloat ? "fmul" : "mul");
                    break;
                case DIVIDE:
                    sb.append(isFloat ? "fdiv" : "sdiv");
                    break;
                case MODULO:
                    sb.append(isFloat ? "frem" : "srem");
                    break;
                case AND:
                    sb.append("and");
                    break;
                case OR:
                    sb.append("or");
                    break;
                case XOR:
                    sb.append("xor");
                    break;
                case SHIFT_LEFT:
                    sb.append("shl");
                    break;
                case SHIFT_RIGHT:
                    sb.append("ashr");
                    break;
                case SHIFT_RIGHT_UNSIGNED:
                    sb.append("lshr");
                    break;
                case COMPARE:
                    sb.append("call i32 @teavm.cmp.");
                    sb.append(typeStr + "(" + typeStr + " " + var(first) + ", " + typeStr + " " + var(second) + ")");
                    assignTo(receiver, sb.toString());
                    return;
            }
            if (type == NumericOperandType.LONG) {
                switch (op) {
                    case SHIFT_LEFT:
                    case SHIFT_RIGHT:
                    case SHIFT_RIGHT_UNSIGNED: {
                        secondString = assignToTmp("sext i32 " + secondString + " to i64");
                        break;
                    }
                    default:
                        break;
                }
            }

            sb.append(" ").append(typeStr).append(" " + var(first) + ", " + secondString);

            assignTo(receiver, sb.toString());
        }

        @Override
        public void negate(VariableReader receiver, VariableReader operand, NumericOperandType type) {
            assignTo(receiver, "sub " + renderType(type) + " 0, " + var(operand));
        }

        @Override
        public void assign(VariableReader receiver, VariableReader assignee) {
            String type = renderType(typeInferer.typeOf(receiver.getIndex()));
            assignTo(receiver, "bitcast " + type + " " + var(assignee) + " to " + type);
        }

        @Override
        public void cast(VariableReader receiver, VariableReader value, ValueType targetType) {
            assignTo(receiver, "bitcast i8* " + var(value) + " to i8*");
        }

        @Override
        public void cast(VariableReader receiver, VariableReader value, NumericOperandType sourceType,
                NumericOperandType targetType) {
            switch (sourceType) {
                case INT:
                    switch (targetType) {
                        case INT:
                        case LONG:
                            assignTo(receiver, "sext i32 " + var(value) + " to " + renderType(targetType));
                            break;
                        case FLOAT:
                        case DOUBLE:
                            assignTo(receiver, "sitofp i32 " + var(value) + " to " + renderType(targetType));
                            break;
                    }
                    break;
                case LONG:
                    switch (targetType) {
                        case INT:
                        case LONG:
                            assignTo(receiver, "trunc i64 " + var(value) + " to " + renderType(targetType));
                            break;
                        case FLOAT:
                        case DOUBLE:
                            assignTo(receiver, "sitofp i64 " + var(value) + " to " + renderType(targetType));
                            break;
                    }
                    break;
                case FLOAT:
                    switch (targetType) {
                        case INT:
                        case LONG:
                            assignTo(receiver, "fptosi float " + var(value) + " to " + renderType(targetType));
                            break;
                        case FLOAT:
                        case DOUBLE:
                            assignTo(receiver, "fpext float " + var(value) + " to " + renderType(targetType));
                            break;
                    }
                    break;
                case DOUBLE:
                    switch (targetType) {
                        case INT:
                        case LONG:
                            assignTo(receiver, "fptosi double " + var(value) + " to " + renderType(targetType));
                            break;
                        case FLOAT:
                        case DOUBLE:
                            assignTo(receiver, "fptrunc double " + var(value) + " to " + renderType(targetType));
                            break;
                    }
                    break;
            }
        }

        @Override
        public void cast(VariableReader receiver, VariableReader value, IntegerSubtype type,
                CastIntegerDirection direction) {
            switch (direction) {
                case TO_INTEGER:
                    assignTo(receiver, "bitcast i32 " + var(value) + " to i32");
                    break;
                case FROM_INTEGER:
                    switch (type) {
                        case BYTE: {
                            String tmp = assignToTmp("trunc i32 " + var(value) + " to i8");
                            assignTo(receiver, "sext i8 " + tmp + " to i32");
                            break;
                        }
                        case SHORT: {
                            String tmp = assignToTmp("trunc i32 " + var(value) + " to i16");
                            assignTo(receiver, "sext i16 " + tmp + " to i32");
                            break;
                        }
                        case CHAR: {
                            String tmp = assignToTmp("trunc i32 " + var(value) + " to i16");
                            assignTo(receiver, "zext i16 " + tmp + " to i32");
                            break;
                        }
                    }
                    break;
            }
        }

        @Override
        public void jumpIf(BranchingCondition cond, VariableReader operand, BasicBlockReader consequent,
                BasicBlockReader alternative) {
            LLVMBlock rememberedOut = out;
            deferredActions.add(() -> {
                out = rememberedOut;
                String type = "i32";
                String second = "0";
                if (cond == BranchingCondition.NULL || cond == BranchingCondition.NOT_NULL) {
                    type = "i8*";
                    second = "null";
                }

                String tmp = assignToTmp("icmp " + render(cond) + " " + type + " " + var(operand) + ", " + second);
                out.line("br i1 " + tmp + ", label " + block(consequent) + ", label " + block(alternative));
            });
        }

        @Override
        public void jumpIf(BinaryBranchingCondition cond, VariableReader first, VariableReader second,
                BasicBlockReader consequent, BasicBlockReader alternative) {
            LLVMBlock rememberedOut = out;
            deferredActions.add(() -> {
                out = rememberedOut;

                String type = "i32";
                String op;
                switch (cond) {
                    case EQUAL:
                        op = "eq";
                        break;
                    case NOT_EQUAL:
                        op = "ne";
                        break;
                    case REFERENCE_EQUAL:
                        op = "eq";
                        type = "i8*";
                        break;
                    case REFERENCE_NOT_EQUAL:
                        op = "ne";
                        type = "i8*";
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown condition: " + cond);
                }

                String tmp = assignToTmp("icmp " + op + " " + type + " " + var(first) + ", " + var(second));
                out.line("br i1 " + tmp + ", label " + block(consequent) + ", label " + block(alternative));
            });
        }

        @Override
        public void jump(BasicBlockReader target) {
            out.line("br label " + block(target));
        }

        @Override
        public void choose(VariableReader condition, List<? extends SwitchTableEntryReader> table,
                BasicBlockReader defaultTarget) {
            LLVMBlock rememberedOut = out;

            deferredActions.add(() -> {
                StringBuilder sb = new StringBuilder();
                sb.append("switch i32 " + var(condition) + ", label " + block(defaultTarget) + " [");
                for (SwitchTableEntryReader entry : table) {
                    sb.append(" i32 " + entry.getCondition() + ", label " + block(entry.getTarget()));
                }
                sb.append(" ]");
                rememberedOut.line(sb.toString());
            });
        }

        @Override
        public void exit(VariableReader valueToReturn) {
            if (method.getResultType() == ValueType.VOID) {
                out.line("ret void");
            } else {
                out.line("ret " + renderType(method.getResultType()) + " " + var(valueToReturn));
            }
        }

        @Override
        public void raise(VariableReader exception) {
            throw new UnsupportedOperationException("Raise instruction must be eliminated before rendering LLVM");
        }

        @Override
        public void createArray(VariableReader receiver, ValueType itemType, VariableReader size) {
            referencedTypes.add(itemType);
            String classRef = classInstance(itemType);
            String allocName = Mangling.mangleMethod(new MethodReference(Allocator.class, "allocateArray",
                    RuntimeClass.class, int.class, Address.class));
            assignTo(receiver, "call i8* " + allocName + "(i8* " + classRef + ", i32 " + var(size) + ")");
        }

        @Override
        public void createArray(VariableReader receiver, ValueType itemType,
                List<? extends VariableReader> dimensions) {
        }

        @Override
        public void create(VariableReader receiver, String type) {
            String classRef = "@class$" + Mangling.mangleType(ValueType.object(type));
            String allocName = Mangling.mangleMethod(new MethodReference(Allocator.class, "allocate",
                    RuntimeClass.class, Address.class));
            assignTo(receiver, "call i8* " + allocName + "(i8* " + classRef + ")");
        }

        @Override
        public void getField(VariableReader receiver, VariableReader instance, FieldReference field,
                ValueType fieldType) {
            assignTo(receiver, getField(instance != null ? var(instance) : null, field, fieldType));
        }

        private String getField(String instance, FieldReference field, ValueType fieldType) {
            String valueTypeRef = renderType(fieldType);
            if (instance == null) {
                return "load " + valueTypeRef + ", " + valueTypeRef + "* @" + mangleField(field);
            } else {
                String pointer = getReferenceToField(instance, field);
                return "load " + valueTypeRef + ", " + valueTypeRef + "* " + pointer;
            }
        }

        @Override
        public void putField(VariableReader instance, FieldReference field, VariableReader value, ValueType fieldType) {
            String valueTypeRef = renderType(fieldType);
            if (instance == null) {
                out.line("store " + valueTypeRef + " " + var(value) + ", " + valueTypeRef + "* @" + mangleField(field));
            } else {
                String pointer = getReferenceToField(var(instance), field);
                out.line("store " + valueTypeRef + " " + var(value) + ", " + valueTypeRef + "* " + pointer);
            }
        }

        private String getReferenceToField(String instance, FieldReference field) {
            String typeRef = dataStruct(field.getClassName());
            String typedInstance = assignToTmp("bitcast i8* " + instance + " to " + typeRef + "*");
            return assignToTmp("getelementptr " + typeRef + ", " + typeRef + "* "
                    + typedInstance + ", i32 0, i32 " + layoutProvider.getIndex(field));
        }

        @Override
        public void arrayLength(VariableReader receiver, VariableReader array) {
            getField(receiver, array, ARRAY_SIZE_FIELD, ValueType.INTEGER);
        }

        @Override
        public void cloneArray(VariableReader receiver, VariableReader array) {

        }

        @Override
        public void unwrapArray(VariableReader receiver, VariableReader array, ArrayElementType elementType) {
            assign(receiver, array);
        }

        @Override
        public void getElement(VariableReader receiver, VariableReader array, VariableReader index,
                ArrayElementType elementType) {
            String type = renderType(typeInferer.typeOf(receiver.getIndex()));
            VariableType itemType = typeInferer.typeOf(array.getIndex());
            String itemTypeStr = renderItemType(itemType);
            String elementRef = getArrayElementReference(array, index, itemTypeStr);
            if (type.equals(itemTypeStr)) {
                assignTo(receiver, "load " + type + ", " + type + "* " + elementRef);
            } else {
                String tmp = assignToTmp("load " + itemTypeStr + ", " + itemTypeStr + "* " + elementRef);
                switch (itemType) {
                    case BYTE_ARRAY:
                        assignTo(receiver, "sext i8 " + tmp + " to i32");
                        break;
                    case SHORT_ARRAY:
                        assignTo(receiver, "sext i16 " + tmp + " to i32");
                        break;
                    case CHAR_ARRAY:
                        assignTo(receiver, "zext i16 " + tmp + " to i32");
                        break;
                    default:
                        throw new AssertionError("Should not get here");
                }
            }
        }

        @Override
        public void putElement(VariableReader array, VariableReader index, VariableReader value,
                ArrayElementType elementType) {
            String type = renderType(typeInferer.typeOf(value.getIndex()));
            VariableType itemType = typeInferer.typeOf(array.getIndex());
            String itemTypeStr = renderItemType(itemType);
            String elementRef = getArrayElementReference(array, index, itemTypeStr);
            String valueRef = "%v" + value.getIndex();
            if (!type.equals(itemTypeStr)) {
                valueRef = assignToTmp("trunc i32 " + valueRef + " to " + itemTypeStr);
            }
            out.line("store " + itemTypeStr + " " + valueRef + ", " + itemTypeStr + "* " + elementRef);
        }

        private String getArrayElementReference(VariableReader array, VariableReader index, String type) {
            String arrayStruct = dataStruct(RuntimeArray.class.getName());
            String objectRef = assignToTmp("bitcast i8* " + var(array) + " to " + arrayStruct + "*");
            String dataRef = assignToTmp("getelementptr " + arrayStruct + ", " + arrayStruct + "* "
                    + objectRef + ", i32 1");
            String typedDataRef = assignToTmp("bitcast " + arrayStruct + "* " + dataRef + " to " + type + "*");
            String adjustedIndex = assignToTmp("add i32 " + var(index) + ", 1");
            return assignToTmp("getelementptr " + type + ", " + type + "* " + typedDataRef
                    + ", i32 %t" + adjustedIndex);
        }

        @Override
        public void invoke(VariableReader receiver, VariableReader instance, MethodReference method,
                List<? extends VariableReader> arguments, InvocationType type) {
            StringBuilder sb = new StringBuilder();
            if (receiver != null) {
                sb.append(var(receiver) + " = ");
            }

            String function;
            if (type == InvocationType.SPECIAL) {
                function = "@" + mangleMethod(method);
            } else {
                VirtualTableEntry entry = resolve(method);
                if (entry != null) {
                    String className = entry.getVirtualTable().getClassName();
                    String typeRef = classStruct(className);
                    String classRef = getClassRef(instance, typeRef);

                    int vtableIndex = entry.getIndex() + 1;
                    String functionRef = assignToTmp("getelementptr inbounds " + typeRef + ", "
                            + typeRef + "* " + classRef + ", i32 0, i32 " + vtableIndex);
                    String methodType = methodType(method.getDescriptor());
                    function = assignToTmp("load " + methodType + ", " + methodType + "* " + functionRef);
                } else {
                    function = "";
                }
            }

            sb.append("call " + renderType(method.getReturnType()) + " " + function + "(");

            List<String> argumentStrings = new ArrayList<>();
            if (instance != null) {
                argumentStrings.add("i8* " + var(instance));
            }
            for (int i = 0; i < arguments.size(); ++i) {
                argumentStrings.add(renderType(method.parameterType(i)) + " " + var(arguments.get(i)));
            }
            sb.append(argumentStrings.stream().collect(Collectors.joining(", ")) + ")");

            out.line(sb.toString());
        }

        private VirtualTableEntry resolve(MethodReference method) {
            while (true) {
                VirtualTableEntry entry = vtableProvider.lookup(method);
                if (entry != null) {
                    return entry;
                }
                ClassReader cls = classSource.get(method.getClassName());
                if (cls == null || cls.getParent() == null || cls.getParent().equals(cls.getName())) {
                    break;
                }
                method = new MethodReference(cls.getParent(), method.getDescriptor());
            }
            return null;
        }

        @Override
        public void invokeDynamic(VariableReader receiver, VariableReader instance, MethodDescriptor method,
                List<? extends VariableReader> arguments, MethodHandle bootstrapMethod,
                List<RuntimeConstant> bootstrapArguments) {

        }

        @Override
        public void isInstance(VariableReader receiver, VariableReader value, ValueType type) {
            assignTo(receiver, "call i32 " + isInstanceFunction(type) + "(i8* " + var(value) + ")");
        }

        @Override
        public void initClass(String className) {
            referencedTypes.add(ValueType.object(className));
            String classRef = assignToTmp("bitcast " + classStruct(className) + "* "
                    + classInstance(ValueType.object(className)) + " to " + dataStruct(CLASS_CLASS) + "*");
            String flags = getField(classRef, CLASS_FLAGS_FIELD, ValueType.INTEGER);
            String initBit = assignToTmp("and i32, " + flags + ", " + RuntimeClass.INITIALIZED);
            String initialized = assignToTmp("icmp ne i32 " + initBit + ", " + 0);
            String labelUninitilized = "%L" + temporaryBlock++;
            String labelInitilized = "%L" + temporaryBlock++;
            out.line("br i1 " + initialized + " label " + labelInitilized + ", label " + labelUninitilized);

            rootBlock.line(labelUninitilized + ":");
            out = rootBlock.innerBlock();
            out.line("call void " + classInitializer(className) + "()");
            out.line("br label " + labelInitilized);

            rootBlock.line(labelInitilized + ":");
            out = rootBlock.innerBlock();
        }

        private String getClassRef(VariableReader instance, String typeRef) {
            String classTag = assignToTmp(getField(var(instance), OBJECT_CLASS_REFERENCE_FIELD, ValueType.INTEGER));
            String classRef = assignToTmp("shl i32 " + classTag + ", 3");
            return assignToTmp("inttoptr i32 " + classRef + " to " + typeRef + "*");
        }

        @Override
        public void nullCheck(VariableReader receiver, VariableReader value) {

        }

        @Override
        public void monitorEnter(VariableReader objectRef) {

        }

        @Override
        public void monitorExit(VariableReader objectRef) {

        }
    };

    private void assignTo(VariableReader receiver, String rhs) {
        out.line("%v" + receiver.getIndex() + " = " + rhs);
    }

    private String assignToTmp(String rhs) {
        String t = tmpVar();
        out.line(t + " = " + rhs);
        return t;
    }

    private String var(VariableReader v) {
        return "%v" + v.getIndex();
    }

    private String tmpVar() {
        return "%t" + temporaryVariable++;
    }

    private String block(BasicBlockReader block) {
        String label = basicBlockLabels[block.getIndex()];
        return label != null ? label : "%b" + block.getIndex();
    }

    private static String render(BranchingCondition cond) {
        switch (cond) {
            case EQUAL:
            case NULL:
                return "eq";
            case NOT_NULL:
            case NOT_EQUAL:
                return "ne";
            case GREATER:
                return "sgt";
            case GREATER_OR_EQUAL:
                return "sge";
            case LESS:
                return "slt";
            case LESS_OR_EQUAL:
                return "sle";
        }
        throw new IllegalArgumentException("Unsupported condition: " + cond);
    }
}
