package org.imnu.smartcommunitycare.elder.model.request;
import jakarta.validation.constraints.Size;
public record UpdateElderRequest(String birthDate,@Size(max=10) String gender,@Size(max=200) String address,
        @Size(max=50) String emergencyContact,@Size(max=20) String emergencyPhone,@Size(max=1000) String careNote) {}
