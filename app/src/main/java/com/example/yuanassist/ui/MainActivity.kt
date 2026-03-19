package com.example.yuanassist.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.yuanassist.R
import com.google.android.material.bottomnavigation.BottomNavigationView
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_dashboard) // 这里对应刚才新写的带底栏的 layout

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)

        // 默认加载运行页面 (HomeFragment)
        if (savedInstanceState == null) {
            loadFragment(HomeFragment())
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    loadFragment(HomeFragment())
                    true
                }
                R.id.nav_strategy -> {
                    // 稍后我们要把 StrategyListActivity 迁进来，现在先放个空的占位
                    loadFragment(StrategyFragment())
                    true
                }
                R.id.nav_profile -> {
                    // 稍后开发的“我的”页面
                    loadFragment(MineFragment())
                    true
                }
                else -> false
            }
        }

    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}