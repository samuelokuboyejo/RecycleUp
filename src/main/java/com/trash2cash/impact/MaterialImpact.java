package com.trash2cash.impact;

import com.trash2cash.users.enums.WasteType;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "material_impact")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MaterialImpact {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(unique = true)
    private WasteType materialType;

    private double co2PerKg;
    private double energyPerKg;
    private double waterPerKg;
    private double treesPerKg;
    private int co2Percent;
}
