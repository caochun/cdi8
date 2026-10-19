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
import org.xml.sax.InputSource;

/** State selection and entry/exit semantics belong entirely to Commons SCXML. */
public final class ScxmlEngine {
    public record Step(String source, String target, List<String> exits, List<String> entries, List<String> transitions) { }
    private final SCXMLExecutor executor;
    private final SCXML model;
    private final List<String> exits = new ArrayList<>(), entries = new ArrayList<>(), transitions = new ArrayList<>();

    public ScxmlEngine(String xml) {
        try {
            secureDocument(xml);
            model = SCXMLReader.read(new StringReader(xml));
            executor = new SCXMLExecutor(new JexlEvaluator(), new SimpleDispatcher(),
                (code, detail, context) -> { throw new IllegalArgumentException("SCXML " + code + ": " + detail); });
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
