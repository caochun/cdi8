package cn.cdi8.workbench;

import cn.cdi8.workbench.model.ModelValidation;
import cn.cdi8.workbench.systemstate.SystemStateModel;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemStateModelTest {
    @Test void gxlfDeclarationDefinesStatesPredicatesAndTransitions() throws Exception {
        var model=SystemStateModel.parse(Files.readString(Path.of("examples/gxlf/system/gxlf-initialization.system.xml")));
        assertThat(model.id()).isEqualTo("gxlf-initialization");
        assertThat(model.initial()).isEqualTo("uninitialized");
        assertThat(model.transition("powerChecksVerified").to()).isEqualTo("powered");
        assertThat(model.state("initialized").predicates()).hasSize(4);
        assertThat(model.state("uninitialized").predicates().get(0).states())
            .contains("statusUnknown", "powerOnChecked");
    }

    @Test void rejectsTransitionsToUnknownStatesAndUnobservableNonInitialStates() {
        String header="<system-state-model xmlns=\""+SystemStateModel.NS+"\" id=\"test\" initial=\"initial\">";
        assertThatThrownBy(()->SystemStateModel.parse(header+
            "<state id=\"initial\" label=\"Initial\"/><state id=\"ready\" label=\"Ready\"/>"+
            "<transition id=\"go\" from=\"initial\" to=\"missing\"/></system-state-model>"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("必须声明设备条件");
        assertThatThrownBy(()->SystemStateModel.parse(header+
            "<state id=\"initial\" label=\"Initial\"/><state id=\"ready\" label=\"Ready\"><require collection=\"targets\" component=\"seed\" states=\"ok\"/></state>"+
            "<transition id=\"go\" from=\"initial\" to=\"missing\"/></system-state-model>"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("引用未定义状态");
    }

    @Test void oldQualificationExtensionsAreRejectedWithMigrationGuidance() throws Exception {
        String xml=Files.readString(Path.of("examples/gxlf/bpmn/component_initialization.bpmn20.xml"))
            .replace("<sys:systemModel ref=\"gxlf-initialization\" />",
                "<sys:systemModel ref=\"gxlf-initialization\" /><ctl:qualification xmlns:ctl=\"https://cdi8.cn/control/qualification/v1\" id=\"legacy\" />");
        var bpmn=ModelValidation.parse(xml);
        assertThatThrownBy(()->SystemStateModel.validateProcess(bpmn))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("资格扩展已移除");
    }
}
