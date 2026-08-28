import { useEffect, useRef } from 'react'
import * as echarts from 'echarts'

interface TrendChartProps {
  labels: string[]
  values: string[]
  title: string
}

/**
 * ECharts 趋势图（SVG 渲染）。
 * 渲染环境不支持 canvas/SVG 初始化时降级为数据表格，金额与结论仍以服务端序列为准。
 */
export function TrendChart({ labels, values, title }: TrendChartProps) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const chartFailed = useRef(false)

  useEffect(() => {
    const element = containerRef.current
    if (!element || labels.length === 0) return
    let chart: echarts.ECharts | null = null
    try {
      chart = echarts.init(element, null, { renderer: 'svg' })
      chart.setOption({
        animation: false,
        grid: { left: 56, right: 16, top: 24, bottom: 28 },
        xAxis: { type: 'category', data: labels },
        yAxis: { type: 'value', scale: true },
        series: [{ name: title, type: 'line', data: values, showSymbol: false, smooth: true }],
        tooltip: { trigger: 'axis' },
      })
    } catch {
      chartFailed.current = true
    }
    const handleResize = () => chart?.resize()
    window.addEventListener('resize', handleResize)
    return () => {
      window.removeEventListener('resize', handleResize)
      chart?.dispose()
      chart = null
    }
  }, [labels, values, title])

  if (labels.length === 0) {
    return <p className="text-sm text-muted-foreground">当前区间没有趋势数据。</p>
  }

  return (
    <div>
      <div ref={containerRef} data-testid="trend-chart" className="h-56 w-full" role="img" aria-label={`${title}趋势图`} />
      <details className="mt-2 text-sm text-muted-foreground" data-testid="trend-table">
        <summary>趋势数据表</summary>
        <table className="mt-2 w-full text-left">
          <thead>
            <tr><th scope="col">日期</th><th scope="col">{title}</th></tr>
          </thead>
          <tbody>
            {labels.map((label, index) => (
              <tr key={label}><td>{label}</td><td>{values[index]}</td></tr>
            ))}
          </tbody>
        </table>
      </details>
    </div>
  )
}
