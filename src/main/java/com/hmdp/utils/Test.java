package com.hmdp.utils;


import cn.hutool.core.date.CalendarUtil;

public class Test {
//    @Builder
    static class A{
        private String name;
        private int age;
        public static ABuilder builder(){
            return new ABuilder();
        }
        static class ABuilder{
            private String name;
            private int age;
            A build(){
                A a = new A();
                a.name = this.name;
                a.age = this.age;
                return a;
            }

            public ABuilder name(String name) {
                this.name = name;
                return this;

            }

            public ABuilder age(int age) {
                this.age = age;
                return this;
            }
        }


    }
    public static void main(String[] args) {
        A.ABuilder builder = A.builder();
        A.ABuilder age = new A.ABuilder().name("123").age(1);

    }


}
