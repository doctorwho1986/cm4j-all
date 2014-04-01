package com.cm4j.test.guava.consist.entity;

// Generated 2013-10-11 18:21:27 by Hibernate Tools 3.4.0.CR1

import com.cm4j.test.guava.consist.cc.CacheEntry;

import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.Table;

/**
 * TmpListMultikey generated by hbm2java
 */
@Entity
@Table(name = "tmp_list_multikey")
public class TmpListMultikey extends CacheEntry implements IEntity, Cloneable {

    private TmpListMultikeyPK id;
    private int NValue;

    public TmpListMultikey() {
    }

    public TmpListMultikey(TmpListMultikeyPK id, int NValue) {
        this.id = id;
        this.NValue = NValue;
    }

    @EmbeddedId
    @AttributeOverrides({
            @AttributeOverride(name = "NPlayerId", column = @Column(name = "n_player_id", nullable = false)),
            @AttributeOverride(name = "NType", column = @Column(name = "n_type", nullable = false))})
    public TmpListMultikeyPK getId() {
        return this.id;
    }

    public void setId(TmpListMultikeyPK id) {
        this.id = id;
    }

    @Column(name = "n_value", nullable = false)
    public int getNValue() {
        return this.NValue;
    }

    public void setNValue(int NValue) {
        this.NValue = NValue;
    }

    @Override
    public IEntity parseEntity() {
        try {
            return (IEntity) this.clone();
        } catch (CloneNotSupportedException e) {
            e.printStackTrace();
        }
        return null;
    }
}
