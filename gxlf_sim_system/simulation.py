"""Explicit positive feedback fixture for demos, never real device evidence."""

from .subsystem_fsm import CompletionEvidence


def simulated_success_evidence(action: str) -> CompletionEvidence:
    observations = {
        'power_on_self_test': '自检完成',
        'function_check': '功能检查完成',
        'parameter_dispatch': '参数下发完成',
        'seed_source_emit': '出光完成',
        'laser_parameter_collect': '采集完成',
        'standby_reset': '复位/待机完成',
        'abort_reset': '复位/待机完成',
        'shutdown': '下电成功',
    }
    return CompletionEvidence(True, observations[action], {
        'power_available': True, 'heartbeat_ok': True, 'service_reachable': True,
        'self_test_passed': True, 'service_running': True, 'function_check_passed': True,
        'capabilities_ok': True, 'interlock_ok': True, 'safe_position': True,
        'no_uncleared_fault': True, 'resources_released': True,
        'configuration_valid': True, 'emission_stable': True,
        'cause_cleared': True, 'reset_verified': True, 'power_off_confirmed': True,
    })
