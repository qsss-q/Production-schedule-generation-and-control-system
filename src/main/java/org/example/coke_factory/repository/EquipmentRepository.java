// repository/EquipmentRepository.java
package org.example.coke_factory.repository;

import org.example.coke_factory.entity.Equipment;
import org.example.coke_factory.entity.EquipmentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EquipmentRepository extends JpaRepository<Equipment, Long> {
    List<Equipment> findByType(EquipmentType type);
}