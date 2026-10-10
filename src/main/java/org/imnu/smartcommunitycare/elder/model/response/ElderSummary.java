package org.imnu.smartcommunitycare.elder.model.response;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
public record ElderSummary(@JsonSerialize(using=ToStringSerializer.class) Long id,
                           @JsonSerialize(using=ToStringSerializer.class) Long userId,String username,String realName,String phone,Boolean enabled,
                           String address,String emergencyContact) {}
