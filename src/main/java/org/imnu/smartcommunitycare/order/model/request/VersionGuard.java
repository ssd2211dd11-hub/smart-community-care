package org.imnu.smartcommunitycare.order.model.request;

import org.imnu.smartcommunitycare.common.exception.BusinessException;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrder;

/** 页面读取的版本必须随动作提交；缺版本拒绝，旧版本冲突，不替换成服务器当前值。 */
public final class VersionGuard {
    private VersionGuard() { }

    public static Integer resolve(Integer clientVersion, ServiceOrder current, String action) {
        if (clientVersion == null || clientVersion < 0) {
            throw BusinessException.badRequest("请携带有效的订单版本");
        }
        if (!clientVersion.equals(current.getVersion())) {
            throw BusinessException.conflict(
                    action + "失败：订单已被他人修改（你的版本 " + clientVersion
                            + "，当前 " + current.getVersion() + "），请刷新后重试");
        }
        return clientVersion;
    }
}
