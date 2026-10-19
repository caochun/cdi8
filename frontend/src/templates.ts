export const scxmlTemplate=`<?xml version="1.0" encoding="UTF-8"?>
<scxml xmlns="http://www.w3.org/2005/07/scxml" version="1.0" initial="operating">
  <datamodel><data id="componentMetadata"><![CDATA[
{
  "model_id": "demo-component-v1", "model_version": 1,
  "display_name": "示例组件",
  "outer_labels": {"operating":"运行"},
  "inner_labels": {"idle":"空闲", "active":"已激活"},
  "context_defaults": {},
  "actions": {
    "activate": {"label":"激活", "requests":{"idle":"activate.request"},
      "success_events":{"idle":"activate.succeeded"},
      "failure_events":{"idle":"activate.failed"},
      "success_key":"激活成功", "failure_key":"激活失败"}
  }
}
  ]]></data></datamodel>
  <state id="operating" initial="idle">
    <state id="idle">
      <transition event="activate.succeeded" target="active" />
      <transition event="activate.failed" target="idle" />
    </state>
    <state id="active" />
  </state>
</scxml>`;
export const bpmnTemplate=`<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
 xmlns:flowable="http://flowable.org/bpmn"
 xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
 xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
 xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
 xmlns:lab="https://cdi8.cn/workbench" targetNamespace="https://cdi8.cn/workbench">
  <bpmn:process id="demo_process" name="示例联调流程" isExecutable="true">
    <bpmn:startEvent id="start" />
    <bpmn:serviceTask id="activate" name="激活模拟组件" flowable:delegateExpression="\u0024{componentCommand}">
      <bpmn:extensionElements>
        <flowable:field name="componentCommand" stringValue="true" />
        <flowable:field name="action" stringValue="activate" />
        <flowable:field name="expectedState" stringValue="active" />
      </bpmn:extensionElements>
      <bpmn:multiInstanceLoopCharacteristics isSequential="false" flowable:collection="\u0024{demoTargets}" flowable:elementVariable="target" />
    </bpmn:serviceTask>
    <bpmn:endEvent id="end" />
    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="activate" />
    <bpmn:sequenceFlow id="f2" sourceRef="activate" targetRef="end" />
  </bpmn:process>
  <bpmndi:BPMNDiagram id="diagram"><bpmndi:BPMNPlane id="plane" bpmnElement="demo_process">
    <bpmndi:BPMNShape id="s1" bpmnElement="start"><dc:Bounds x="60" y="100" width="36" height="36" /></bpmndi:BPMNShape>
    <bpmndi:BPMNShape id="s2" bpmnElement="activate"><dc:Bounds x="170" y="78" width="150" height="80" /></bpmndi:BPMNShape>
    <bpmndi:BPMNShape id="s3" bpmnElement="end"><dc:Bounds x="400" y="100" width="36" height="36" /></bpmndi:BPMNShape>
    <bpmndi:BPMNEdge id="e1" bpmnElement="f1"><di:waypoint x="96" y="118" /><di:waypoint x="170" y="118" /></bpmndi:BPMNEdge>
    <bpmndi:BPMNEdge id="e2" bpmnElement="f2"><di:waypoint x="320" y="118" /><di:waypoint x="400" y="118" /></bpmndi:BPMNEdge>
  </bpmndi:BPMNPlane></bpmndi:BPMNDiagram>
</bpmn:definitions>`;
