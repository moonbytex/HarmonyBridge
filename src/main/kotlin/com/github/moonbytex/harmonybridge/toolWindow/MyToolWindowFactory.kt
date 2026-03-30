package com.github.moonbytex.harmonybridge.toolWindow

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.github.moonbytex.harmonybridge.services.DashScopeService
import com.github.moonbytex.harmonybridge.services.MyProjectService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.ScrollPaneConstants
import javax.swing.Box
import javax.swing.BoxLayout
import java.awt.FlowLayout


class MyToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val chatToolWindow = ChatToolWindow(toolWindow)
        val content = ContentFactory.getInstance().createContent(chatToolWindow.getContent(), null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true

    class ChatToolWindow(toolWindow: ToolWindow) {
        private val service = toolWindow.project.service<MyProjectService>()
        private val dashScopeService = toolWindow.project.service<DashScopeService>()
        
        // 消息历史
        private val chatHistory = mutableListOf<DashScopeService.ChatMessage>()
        
        // 主面板
        private val mainPanel = JBPanel<JBPanel<*>>()
        
        // 聊天记录显示区域
        private val chatPanel = JBPanel<JBPanel<*>>()
        private val scrollPane: JScrollPane
        
        // 输入框和发送按钮
        private val inputField = JTextArea().apply {
            lineWrap = true
            wrapStyleWord = true
            rows = 3
            margin = Insets(8, 8, 8, 8)
            font = java.awt.Font("Microsoft YaHei", java.awt.Font.PLAIN, 14)
        }
        private val clearButton = JButton("🗑 清除").apply {
            preferredSize = Dimension(70, 30)
            margin = Insets(8, 12, 8, 12)
        }
        private val sendButton = JButton("发送").apply {
            preferredSize = Dimension(80, 30)
            margin = Insets(8, 16, 8, 16)
        }
        
        init {
            // 设置聊天面板布局
            chatPanel.layout = BoxLayout(chatPanel, BoxLayout.Y_AXIS)
            chatPanel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            
            // 创建滚动面板
            scrollPane = JBScrollPane(chatPanel).apply {
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                border = BorderFactory.createEmptyBorder()
            }
            
            // 设置输入区域
            val inputPanel = JBPanel<JBPanel<*>>().apply {
                layout = BorderLayout()
                border = BorderFactory.createMatteBorder(1, 0, 0, 0, Color(200, 200, 200))
                preferredSize = Dimension(-1, 100)
            }
            
            // 右侧按钮面板（清除和发送按钮）
            val rightButtonPanel = JBPanel<JBPanel<*>>().apply {
                layout = FlowLayout(FlowLayout.RIGHT, 5, 5)
                isOpaque = false
                add(clearButton)
                add(sendButton)
            }
            
            inputPanel.add(JBScrollPane(inputField).apply {
                border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
            }, BorderLayout.CENTER)
            
            inputPanel.add(rightButtonPanel, BorderLayout.EAST)
            
            // 主面板布局
            mainPanel.layout = BorderLayout()
            mainPanel.add(scrollPane, BorderLayout.CENTER)
            mainPanel.add(inputPanel, BorderLayout.SOUTH)
            
            // 发送按钮事件
            sendButton.addActionListener { sendMessage() }
            
            // 清除按钮事件
            clearButton.addActionListener { clearChat() }
            
            // 添加欢迎消息
            addWelcomeMessage()
        }
        
        private fun addWelcomeMessage() {
            addMessage("assistant", "你好！我是 HarmonyBridge 助手，有什么可以帮你的吗？")
        }
        
        private fun sendMessage() {
            val text = inputField.text.trim()
            if (text.isEmpty()) return
            
            // 禁用输入框防止重复发送
            inputField.isEnabled = false
            sendButton.isEnabled = false
            
            // 添加用户消息
            addMessage("user", text)
            chatHistory.add(DashScopeService.ChatMessage("user", text))
            
            // 清空输入框
            inputField.text = ""
            
            // 创建模型回复的消息气泡，先显示"正在思考..."
            val assistantMessagePanel = createMessageBubble("assistant", "⏳ 正在思考...")
            chatPanel.add(assistantMessagePanel)
            scrollToBottom()
            
            var currentText = ""
            var isThinking = false
            val thinkingContent = StringBuilder()
            
            // 发送请求到 DashScope
            dashScopeService.sendChatStream(chatHistory, object : DashScopeService.StreamCallback {
                override fun onThinking(content: String) {
                    if (!isThinking) {
                        isThinking = true
                        thinkingContent.clear()
                        thinkingContent.append("<think>")
                    }
                    thinkingContent.append(content)
                    updateLastMessage(thinkingContent.toString())
                }
                
                override fun onResponse(content: String) {
                    if (isThinking) {
                        thinkingContent.append("</think>\n\n")
                        isThinking = false
                    }
                    thinkingContent.append(content)
                    updateLastMessage(thinkingContent.toString())
                }
                
                override fun onError(error: String) {
                    thisLogger().warn("模型回复错误：$error")
                    updateLastMessage("❌ 错误：$error")
                    inputField.isEnabled = true
                    sendButton.isEnabled = true
                    inputField.requestFocus()
                }
                
                override fun onComplete() {
                    // 保存助手回复到历史记录
                    chatHistory.add(DashScopeService.ChatMessage("assistant", thinkingContent.toString()))
                    
                    // 重新启用输入
                    inputField.isEnabled = true
                    sendButton.isEnabled = true
                    inputField.requestFocus()
                }
            })
        }
        
        private fun addMessage(role: String, content: String) {
            val messagePanel = createMessageBubble(role, content)
            chatPanel.add(messagePanel)
            scrollToBottom()
        }
        
        private fun createMessageBubble(role: String, content: String): JPanel {
            val outerPanel = JBPanel<JBPanel<*>>()
            outerPanel.layout = BoxLayout(outerPanel, BoxLayout.Y_AXIS)
            outerPanel.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            outerPanel.alignmentX = Component.LEFT_ALIGNMENT
            
            // 角色标签 - 始终显示，使用 HTML 格式让 emoji 和中文使用不同字体
            val roleText = if (role == "user") {
                "<html><span style='font-family: Segoe UI Emoji;'>👤</span> <span style='font-family: Microsoft YaHei;'>用户</span></html>"
            } else {
                "<html><span style='font-family: Segoe UI Emoji;'>🤖</span> <span style='font-family: Microsoft YaHei;'>助手</span></html>"
            }
            
            val roleLabel = JBLabel(roleText).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                foreground = if (role == "user") Color(0, 102, 204) else Color(100, 100, 100)
                font = java.awt.Font("Microsoft YaHei", java.awt.Font.BOLD, 13)
                maximumSize = Dimension(Int.MAX_VALUE, 22)
                border = BorderFactory.createEmptyBorder(5, 3, 3, 0)
            }
            
            // 消息内容 - 使用 HTML 实现自动换行，增大字体
            val messageLabel = JBLabel("<html><div style='width: 100%; white-space: pre-wrap;'>$content</div></html>").apply {
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
                font = java.awt.Font("Microsoft YaHei", java.awt.Font.PLAIN, 15)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color(230, 230, 230)),
                    BorderFactory.createEmptyBorder(8, 12, 8, 12)
                )
            }
            
            outerPanel.add(roleLabel)
            outerPanel.add(messageLabel)
            
            return outerPanel
        }
        
        private fun updateLastMessage(content: String) {
            if (chatPanel.componentCount > 0) {
                val lastComponent = chatPanel.getComponent(chatPanel.componentCount - 1)
                if (lastComponent is JPanel && lastComponent.componentCount >= 2) {
                    val messageLabel = lastComponent.getComponent(1)
                    if (messageLabel is JBLabel) {
                        messageLabel.text = "<html><div style='width: 100%; white-space: pre-wrap;'>$content</div></html>"
                        // 优化：减少滚动调用频率，只在更新时触发重绘
                        messageLabel.revalidate()
                        messageLabel.repaint()
                    }
                }
            }
        }
        
        private fun scrollToBottom() {
            val verticalBar = scrollPane.verticalScrollBar
            verticalBar.value = verticalBar.maximum
        }
        
        private fun clearChat() {
            // 清空聊天面板
            chatPanel.removeAll()
            chatPanel.revalidate()
            chatPanel.repaint()
            
            // 清空消息历史
            chatHistory.clear()
            
            // 重新添加欢迎消息
            addWelcomeMessage()
        }
        
        fun getContent() = mainPanel
    }
}
