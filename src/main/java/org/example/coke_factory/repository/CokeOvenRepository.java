package org.example.coke_factory.repository;

import org.example.coke_factory.entity.CokeOvens;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CokeOvenRepository extends JpaRepository<CokeOvens, Long> {

    // поиск печи по названию («Печь П-12»)
    Optional<CokeOvens> findByNameOven(String nameOven);
}
