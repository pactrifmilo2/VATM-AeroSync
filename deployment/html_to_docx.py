from bs4 import BeautifulSoup
from docx import Document
from docx.shared import Inches, Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT, WD_CELL_VERTICAL_ALIGNMENT

src='deployment/AEROSYNC-USER-GUIDE.html'; out='deployment/HUONG-DAN-SU-DUNG-AEROSYNC.docx'
soup=BeautifulSoup(open(src,encoding='utf-8'),'html.parser'); d=Document()
sec=d.sections[0]; sec.top_margin=Inches(.7); sec.bottom_margin=Inches(.7); sec.left_margin=Inches(.8); sec.right_margin=Inches(.8)
style=d.styles['Normal']; style.font.name='Arial'; style.font.size=Pt(10.5)
for el in soup.body.find_all(['h1','h2','h3','p','pre','ul','ol','table'], recursive=False):
    if el.name=='h1':
        p=d.add_paragraph(); p.alignment=WD_ALIGN_PARAGRAPH.CENTER; r=p.add_run(el.get_text(' ',strip=True)); r.bold=True; r.font.size=Pt(24); r.font.color.rgb=RGBColor(22,58,99)
    elif el.name in ('h2','h3'):
        p=d.add_heading(el.get_text(' ',strip=True), level=1 if el.name=='h2' else 2); p.runs[0].font.color.rgb=RGBColor(29,86,136)
    elif el.name=='p':
        p=d.add_paragraph(el.get_text(' ',strip=True))
    elif el.name=='pre':
        p=d.add_paragraph(); r=p.add_run(el.get_text()); r.font.name='Consolas'; r.font.size=Pt(8.5)
    elif el.name in ('ul','ol'):
        for li in el.find_all('li',recursive=False): d.add_paragraph(li.get_text(' ',strip=True), style='List Bullet' if el.name=='ul' else 'List Number')
    elif el.name=='table':
        rows=el.find_all('tr'); cols=max(len(x.find_all(['th','td'],recursive=False)) for x in rows); t=d.add_table(rows=0, cols=cols); t.style='Table Grid'; t.alignment=WD_TABLE_ALIGNMENT.CENTER
        for row in rows:
            cells=t.add_row().cells
            for i,x in enumerate(row.find_all(['th','td'],recursive=False)):
                cells[i].text=x.get_text(' ',strip=True); cells[i].vertical_alignment=WD_CELL_VERTICAL_ALIGNMENT.CENTER
                if x.name=='th':
                    for run in cells[i].paragraphs[0].runs: run.bold=True
d.save(out); print(out)
