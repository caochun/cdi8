package cn.cdi8.workbench;

import cn.cdi8.workbench.model.ModelValidation;
import cn.cdi8.workbench.qualification.QualificationModel;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class QualificationModelTest {
    private String initialization() throws Exception {
        return Files.readString(Path.of("examples/gxlf/bpmn/component_initialization.bpmn20.xml"));
    }
    private void invalid(String xml,String message) {
        assertThatThrownBy(()->QualificationModel.validate(ModelValidation.parse(xml)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }
    @Test void initializationDeclaresExecutableEntryDependencyAndCompletionContracts() throws Exception {
        String xml=initialization();
        QualificationModel.validate(ModelValidation.parse(xml));
        assertThat(xml).doesNotContain("ctl:entryCondition");
        assertThat(xml.split("<ctl:actionContract />",-1)).hasSize(9);
        QualificationModel.validate(ModelValidation.parse(withExtraCondition()));
    }
    @Test void missingAndCyclicQualificationDependenciesAreRejected() throws Exception {
        invalid(initialization().replace("dependsOn=\"powerQualified\"","dependsOn=\"missing\""),"未定义依赖资格");
        invalid(initialization().replace("id=\"powerQualified\" targets=\"allComponents\"",
            "id=\"powerQualified\" targets=\"allComponents\" dependsOn=\"functionQualified\""),"依赖存在循环");
    }
    @Test void qualificationsNeedProducersAndDependenciesNeedDispatchGuards() throws Exception {
        invalid(initialization().replace("<ctl:establish qualificationRef=\"powerQualified\" on=\"verifiedSuccess\" />",""),"缺少建立活动");
        invalid(initialization().replace("<ctl:require qualificationRef=\"powerQualified\" targets=\"allComponents\" quantifier=\"all\" at=\"dispatch\" />",""),"必须声明下发前依赖资格");
    }
    @Test void guardedActionsRequireAnEndConditionAndBoundedWaiting() throws Exception {
        invalid(initialization().replaceFirst("<flowable:field name=\"expectedState\"[^>]*/>",""),"缺少 expectedState");
        invalid(initialization().replaceFirst("(?s)<bpmn:boundaryEvent id=\"seed_power_timeout\".*?</bpmn:boundaryEvent>",""),"缺少中断型超时边界");
        invalid(withExtraCondition().replace("idle=\"true\"","idle=\"false\""),"必须要求 idle=true");
        invalid(initialization().replace("<ctl:actionContract />",""),"必须引用设备 actionContract");
    }
    @Test void unsupportedConstraintsAndMisspelledEntryConditionsAreNotSilentlyIgnored() throws Exception {
        invalid(withExtraCondition().replace("ctl:entryCondition","ctl:entrycondition"),"不支持的 ctl 约束声明");
        invalid(withExtraCondition().replace("idle=\"true\"","idle=\"true\" during=\"execution\""),"不支持的条件属性");
        invalid(initialization().replace("<ctl:actionContract />","<ctl:actionContract states=\"unchecked\" />"),"不接受属性");
        invalid(initialization().replace("<ctl:actionContract />","<ctl:actionContract /><ctl:actionContract />"),"单个组件命令");
        invalid(initialization().replace("id=\"functionQualified\" targets=\"allComponents\"",
            "id=\"functionQualified\" targets=\"differentTargets\""),"必须使用相同 targets");
    }
    private String withExtraCondition() throws Exception {
        return initialization().replaceFirst("<ctl:actionContract />",
            "<ctl:actionContract /><ctl:entryCondition states=\"statusUnknown\" idle=\"true\" />");
    }
}
