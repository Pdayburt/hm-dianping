package com.hmdp.utils;

public interface ILock {

    boolean tryLock(String name,long timeOutSec);
    void unlock(String name);

}
