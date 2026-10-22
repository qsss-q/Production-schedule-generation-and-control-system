package org.example.coke_factory.repository;
import org.example.coke_factory.entity.CokeOvens;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CokeOvenRepository extends JpaRepository<CokeOvens, Long>{

}