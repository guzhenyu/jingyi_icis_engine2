package com.jingyicare.jingyi_icis_engine.entity.medications;

import jakarta.persistence.*;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Entity
@Table(name = "administration_route_groups")
public class AdministrationRouteGroup {
    @Id
    @Column(name = "id")
    private Integer id;

    @Column(name = "name")
    private String name;
}