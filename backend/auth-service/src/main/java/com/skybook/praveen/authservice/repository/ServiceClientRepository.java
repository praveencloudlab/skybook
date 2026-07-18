package com.skybook.praveen.authservice.repository;

import com.skybook.praveen.authservice.entity.ServiceClient;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceClientRepository extends JpaRepository<ServiceClient, String> {
}
