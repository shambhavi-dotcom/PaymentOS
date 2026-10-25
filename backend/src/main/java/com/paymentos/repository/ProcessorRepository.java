package com.paymentos.repository;

import com.paymentos.entity.Processor;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ProcessorRepository extends JpaRepository<Processor, Long> {
    List<Processor> findByEnabledTrueOrderByIdAsc();
    Optional<Processor> findByName(String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Processor p where p.enabled = true order by p.id")
    List<Processor> findEnabledForRouting();
}
