package cn.cdi8.simulator.engine;

import java.io.StringReader;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.commons.scxml2.*;
import org.apache.commons.scxml2.env.SimpleDispatcher;
import org.apache.commons.scxml2.env.jexl.JexlEvaluator;
import org.apache.commons.scxml2.io.SCXMLReader;
import org.apache.commons.scxml2.model.*;
import org.apache.commons.scxml2.semantics.SCXMLSemanticsImpl;
import org.apache.commons.jexl3.*;
import org.xml.sax.InputSource;

/** State selection and entry/exit semantics belong entirely to Commons SCXML. */
public final class ScxmlEngine {
    public record Step(String source, String target, List<String> exits, List<String> entries, List<String> transitions) { }
    private final SCXMLExecutor executor;
    private final SCXML model;
    private final InspectableSemantics semantics = new InspectableSemantics();
    private final List<String> exits = new ArrayList<>(), entries = new ArrayList<>(), transitions = new ArrayList<>();

    public ScxmlEngine(String xml) {
        try {
            var document=secureDocument(xml);
            var declarations=document.getElementsByTagNameNS("http://www.w3.org/2005/07/scxml","transition");
            for(int i=0;i<declarations.getLength();i++) {
                var transition=(org.w3c.dom.Element)declarations.item(i);
                if(transition.hasAttribute("cond")) PureGuardEvaluator.validate(transition.getAttribute("cond"));
            }
            model = SCXMLReader.read(new StringReader(xml));
            executor = new SCXMLExecutor(new PureGuardEvaluator(), new SimpleDispatcher(),
                (code, detail, context) -> { throw new IllegalArgumentException("SCXML " + code + ": " + detail); },semantics);
            executor.setStateMachine(model);
            executor.addListener(model, new SCXMLListener() {
                public void onEntry(EnterableState state) { entries.add(state.getId()); }
                public void onExit(EnterableState state) { exits.add(state.getId()); }
                public void onTransition(TransitionTarget from, TransitionTarget to, Transition transition, String event) {
                    transitions.add(from.getId() + "->" + to.getId());
                }
            });
            executor.go();
            clear();
        } catch (Exception ex) { throw new IllegalArgumentException("无法加载 SCXML：" + ex.getMessage(), ex); }
    }

    /** Guard expressions are predicates. Reject assignments and object method
     * calls at model load so selecting transitions cannot execute device work.
     * SCXML In() remains available through the library's standard evaluator. */
    private static final class PureGuardEvaluator extends JexlEvaluator {
        private static final JexlEngine SYNTAX=new JexlBuilder().features(new JexlFeatures()
            .sideEffect(false).methodCall(false).newInstance(false).lambda(false)).create();
        static void validate(String expression) {
            try { SYNTAX.createExpression(expression); }
            catch(RuntimeException ex) { throw new IllegalArgumentException("SCXML cond 必须是无副作用的条件表达式："+expression,ex); }
        }
        @Override public Boolean evalCond(Context context,String expression) {
            try { return super.evalCond(context,expression); }
            catch(SCXMLExpressionException ex) {
                // Do not let the library enqueue error.execution during a query.
                throw new IllegalStateException("SCXML 守卫求值失败："+expression,ex);
            }
        }
    }
    /** Use Apache's actual transition selection, including ancestor priority,
     * event descriptors and guards, without executing a microstep or callbacks. */
    private static final class InspectableSemantics extends SCXMLSemanticsImpl {
        private SCXMLExecutionContext context;
        @Override public void initialize(SCXMLExecutionContext context,Map<String,Object> data) throws ModelException {
            this.context=context;
            super.initialize(context,data);
        }
        boolean enabled(String event) throws ModelException {
            if(!context.isRunning()) return false;
            var system=context.getScInstance().getSystemContext();
            boolean hadEvent=system.hasLocal(SCXMLSystemContext.EVENT_KEY);
            Object previous=system.get(SCXMLSystemContext.EVENT_KEY);
            try {
                var trigger=new TriggerEvent(event,TriggerEvent.SIGNAL_EVENT);
                setSystemEventVariable(context.getScInstance(),trigger,false);
                var step=new org.apache.commons.scxml2.semantics.Step(trigger);
                selectTransitions(context,step);
                return !step.getTransitList().isEmpty();
            } finally {
                if(hadEvent) system.setLocal(SCXMLSystemContext.EVENT_KEY,previous);
                else system.getVars().remove(SCXMLSystemContext.EVENT_KEY);
            }
        }
    }

    public static org.w3c.dom.Document secureDocument(String xml) throws Exception {
        if (xml == null || xml.length() > 1_000_000) throw new IllegalArgumentException("SCXML 为空或超过 1 MB");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private void clear() { exits.clear(); entries.clear(); transitions.clear(); }
    public Set<String> configuration() {
        var result = new TreeSet<String>();
        for (var state : executor.getStatus().getStates()) result.add(state.getId());
        return Collections.unmodifiableSet(result);
    }
    public String leaf() {
        var states = configuration();
        if (states.size() != 1) throw new IllegalStateException("当前组件协议要求一个有效叶子状态：" + states);
        return states.iterator().next();
    }
    public Step dispatch(String event) {
        String source = leaf(); clear();
        try { executor.triggerEvent(new TriggerEvent(event, TriggerEvent.SIGNAL_EVENT)); }
        catch (Exception ex) { throw new IllegalStateException("SCXML 事件失败：" + event, ex); }
        return new Step(source, leaf(), List.copyOf(exits), List.copyOf(entries), List.copyOf(transitions));
    }
    public boolean enabled(String event) {
        try { return semantics.enabled(event); }
        catch(ModelException ex) { throw new IllegalStateException("SCXML 迁移选择失败："+event,ex); }
    }
    public void restore(String leaf) {
        try {
            var target = model.getTargets().get(leaf);
            if (!(target instanceof EnterableState state) || !state.isAtomicState())
                throw new IllegalArgumentException("要求有效叶子状态");
            // Apache validates the atomic leaf and enters its ancestor configuration.
            executor.setConfiguration(Set.of(leaf));
            if (!leaf.equals(leaf())) throw new IllegalStateException("状态恢复不匹配");
            clear();
        } catch (Exception ex) { throw new IllegalStateException("无法恢复 SCXML 状态：" + leaf, ex); }
    }
}
