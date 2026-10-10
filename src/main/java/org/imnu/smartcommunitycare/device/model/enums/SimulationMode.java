package org.imnu.smartcommunitycare.device.model.enums;

/**
 * 内置模拟器的执行场景。
 *
 * <p>三个场景各有明确语义，不用随机数演示——答辩时需要可预期的结果。
 */
public enum SimulationMode {

    /** 正常执行：设置目标开关，指令成功 */
    NORMAL,

    /** 下一条指令失败一次，开关不变，之后自动恢复 NORMAL */
    FAIL_NEXT,

    /**
     * 设备已改变开关但不回报结果，指令到期为 TIMED_OUT。
     *
     * <p>用于演示「设备在线 ≠ 某条指令已确认」：超时不代表没执行，
     * 页面应写「结果未确认」而不是「设备一定未开启」。
     */
    NO_ACK
}
